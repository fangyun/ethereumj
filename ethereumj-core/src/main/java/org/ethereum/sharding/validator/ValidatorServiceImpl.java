/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.sharding.validator;

import org.ethereum.core.Block;
import org.ethereum.core.BlockSummary;
import org.ethereum.crypto.HashUtil;
import org.ethereum.db.BlockStore;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.sharding.config.ValidatorConfig;
import org.ethereum.sharding.domain.Beacon;
import org.ethereum.sharding.domain.Validator;
import org.ethereum.sharding.processing.state.AttestationRecord;
import org.ethereum.sharding.processing.state.Committee;
import org.ethereum.sharding.pubsub.BeaconBlockAttested;
import org.ethereum.sharding.pubsub.BeaconBlockImported;
import org.ethereum.sharding.pubsub.Publisher;
import org.ethereum.sharding.processing.BeaconChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.Math.max;
import static org.ethereum.sharding.processing.consensus.BeaconConstants.SLOT_DURATION;
import static org.ethereum.sharding.util.BeaconUtils.calcNextAssignedSlot;
import static org.ethereum.sharding.util.BeaconUtils.getCurrentSlotNumber;
import static org.ethereum.sharding.util.BeaconUtils.getSlotStartTime;
import static org.ethereum.sharding.util.BeaconUtils.scanCommittees;

/**
 * Implementation of {@link ValidatorService} that is based on {@link ScheduledExecutorService}.
 *
 * @author Mikhail Kalinin
 * @since 28.08.2018
 */
public class ValidatorServiceImpl implements ValidatorService {

    private static final Logger logger = LoggerFactory.getLogger("validator");

    BeaconProposer proposer;
    BeaconAttester attester;
    BeaconChain beaconChain;
    Publisher publisher;
    ValidatorConfig config;
    Ethereum ethereum;
    BlockStore blockStore;

    private ScheduledExecutorService executor;
    private Map<Integer, ScheduledFuture> currentTasks = new ConcurrentHashMap<>();
    private Map<Integer, byte[]> pubKeysMap = new ConcurrentHashMap<>();
    private Set<Integer> validatorIndices;
    private long lastStateRecalc;
    private ChainHead head;
    private byte[] mainChainRef;

    public ValidatorServiceImpl(BeaconProposer proposer, BeaconAttester attester, BeaconChain beaconChain,
                                Publisher publisher, ValidatorConfig config, Ethereum ethereum, BlockStore blockStore) {
        this.proposer = proposer;
        this.attester = attester;
        this.beaconChain = beaconChain;
        this.publisher = publisher;
        this.config = config;
        this.ethereum = ethereum;
        this.blockStore = blockStore;
    }

    @Override
    public void init(ChainHead head, byte[]... pubKeys) {
        assert pubKeys.length > 0;

        this.validatorIndices = new HashSet<>();
        this.head = head;
        this.mainChainRef = getMainChainRef(blockStore.getBestBlock());

        for (byte[] pubKey : pubKeys) {
            Validator validator = this.head.state.getValidatorSet().getByPubKey(pubKey);
            if (validator != null) {
                this.validatorIndices.add(validator.getIndex());
                this.pubKeysMap.put(validator.getIndex(), validator.getPubKey());
            } else {
                // something went wrong
                logger.error("Failed to start proposer for {}: validator does not exist", HashUtil.shortHash(pubKey));
                return;
            }

            this.validatorIndices.add(validator.getIndex());
        }

        this.executor = Executors.newSingleThreadScheduledExecutor((r) -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("beacon-proposer-thread");
            t.setDaemon(true);
            return t;
        });

        this.lastStateRecalc = this.head.state.getCrystallizedState().getLastStateRecalc();

        // listen to state updates
        publisher.subscribe(BeaconBlockImported.class, (data) -> {
            if (!data.isBest())
                return;

            // trigger only if crystallized state has been recalculated
            if (data.getState().getCrystallizedState().getLastStateRecalc() > lastStateRecalc) {
                this.lastStateRecalc = data.getState().getCrystallizedState().getLastStateRecalc();
                this.submitIfAssigned(data.getState().getCommittees());
            }

            // do not keep anything in mem if chain is not yet synced
            if (this.head != null) {
                this.head = new ChainHead(data.getBlock(), data.getState());
            }
        });

        // update main chain ref
        ethereum.addListener(new EthereumListenerAdapter() {
            @Override
            public void onBlock(BlockSummary blockSummary, boolean best) {
                if (best)
                    mainChainRef = getMainChainRef(blockSummary.getBlock());
            }
        });

        // and finally submit initial tasks
        submitIfAssigned(this.head.state.getCommittees());
    }

    byte[] getMainChainRef(Block mainChainHead) {
        return blockStore.getBlockHashByNumber(max(0L, mainChainHead.getNumber() - REORG_SAFE_DISTANCE));
    }

    private void submitIfAssigned(Committee[][] committees) {
        // validator from only the first committee is eligible to work on beacon chain
        List<Committee.Index> indices = scanCommittees(validatorIndices, committees)
                .stream().filter(idx -> idx.getCommitteeIdx() == 0).collect(Collectors.toList());
        indices.sort((i1, i2) -> Integer.compare(i1.getSlotOffset(), i2.getSlotOffset()));

        for (Committee.Index index : indices) {
            // get number of the next slot that validator is eligible to propose
            long slotNumber = calcNextAssignedSlot(getCurrentSlotNumber(), index.getSlotOffset());

            // not an obvious way of calculating proposer index,
            // proposer = committee[X % len(committee)], X = slotNumber
            // taken from the spec
            if (slotNumber % index.getCommitteeSize() == index.getArrayIdx()) {
                this.propose(slotNumber, index);
            } else {
                this.attest(slotNumber, index);
            }
        }
    }

    @Override
    public void propose(long slotNumber, Committee.Index index) {
        long delay = submit(0L, slotNumber, index.getValidatorIdx(), () -> {
            BeaconProposer.Input input = new BeaconProposer.Input(slotNumber, index, head, mainChainRef);
            Beacon newBlock = proposer.createNewBlock(input, pubKeysMap.get(index.getValidatorIdx()));
            beaconChain.insert(newBlock);
            return newBlock;
        });

        if (delay >= 0) logger.info("Proposer {}: schedule new slot #{} in {}ms", index.getValidatorIdx(), slotNumber, delay);
    }

    @Override
    public void attest(long slotNumber, Committee.Index index) {
        // attester's job should be triggered in the middle of slot's time period
        long delay = submit(SLOT_DURATION / 2, slotNumber, index.getValidatorIdx(), () -> {
            AttestationRecord attestation = attester.attestBlock(
                    slotNumber,
                    index,
                    beaconChain.getCanonicalHead(), // FIXME: how could we be sure that it's a correct block?
                    pubKeysMap.get(index.getValidatorIdx())
            );
            logger.info("Attestation by #{} on slot #{}", index.getValidatorIdx(), slotNumber);
            publisher.publish(new BeaconBlockAttested(attestation));
            return attestation;
        });

        if (delay >= 0) logger.info("Attester {}: schedule new slot #{} in {}ms", index.getValidatorIdx(), slotNumber, delay);
    }

    <T> long submit(long delayShiftMillis, long slotNumber, int validatorIdx, Supplier<T> supplier) {
        if (executor == null) return -1L;

        // skip slots that start in the past
        if (slotNumber <= getCurrentSlotNumber())
            return -1L;

        // always cancel current task and create a new one
        if (currentTasks.containsKey(validatorIdx))
            currentTasks.get(validatorIdx).cancel(false);

        long delayMillis = getSlotStartTime(slotNumber) + delayShiftMillis - System.currentTimeMillis();
        ScheduledFuture newTask = executor.schedule(() -> {
            try {
                return supplier.get();
            } catch (Throwable t) {
                logger.error("Failed to execute validator task", t);
                throw t;
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        currentTasks.put(validatorIdx, newTask);

        return delayMillis;
    }
}