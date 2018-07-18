package org.ethereum.sharding;

import org.ethereum.datasource.DbSource;
import org.ethereum.datasource.inmem.HashMapDBSimple;
import org.junit.Test;

import static org.ethereum.crypto.HashUtil.sha3;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * @author Mikhail Kalinin
 * @since 17.07.2018
 */
public class RandaoTest {

    @Test
    public void testBasics() {
        Randao rnd = new Randao(new HashMapDBSimple<>());
        int rounds = 1 << 10;

        rnd.generate(rounds);

        byte[] preImg = rnd.reveal();
        assertNotNull(preImg);

        for (int i = 0; i < rounds - 1; i++) {
            byte[] img = rnd.reveal();
            assertArrayEquals(preImg, sha3(img));
            preImg = img;
        }

        assertNull(rnd.reveal());
    }

    @Test
    public void testPersistedState() {
        DbSource<byte[]> src = new HashMapDBSimple<>();
        int rounds = 1 << 10;

        // generate
        Randao rnd = new Randao(src);
        rnd.generate(rounds);

        // reveal a half
        int i = 0;
        byte[] img = rnd.reveal();
        for (; i < rounds / 2; i++) {
            img = rnd.reveal();
        }

        // re-init and reveal the others
        rnd = new Randao(src);
        byte[] preImg = img;
        for (; i < rounds - 1; i++) {
            img = rnd.reveal();
            assertArrayEquals(preImg, sha3(img));
            preImg = img;
        }
    }
}