/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.tpcengine.iouring;

import com.hazelcast.internal.tpcengine.iobuffer.IOBuffer;
import org.junit.Assert;
import org.junit.Test;

import static com.hazelcast.internal.tpcengine.util.BitUtil.SIZEOF_INT;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IOVectorTest {

    @Test
    public void test_construction() {
        IOVector v = new IOVector(64);

        Assert.assertEquals(64, v.capacity());
        Assert.assertEquals(0, v.cnt());
        assertTrue(v.isEmpty());
    }

    @Test
    public void test_offer_whenNull() {
        IOVector v = new IOVector(16);
        assertThrows(NullPointerException.class, () -> v.offer(null));
    }

    @Test
    public void test_offer_whenNotDirect() {
        IOVector v = new IOVector(16);
        assertThrows(IllegalArgumentException.class, () -> v.offer(new IOBuffer(1024, false)));
    }

    @Test
    public void test_offer_whenFull() {
        int capacity = 16;
        IOVector v = new IOVector(capacity);
        for (int k = 0; k < v.capacity(); k++) {
            v.offer(new IOBuffer(1024, true));
        }

        boolean result = v.offer(new IOBuffer(1024, true));

        assertFalse(result);
        Assert.assertEquals(capacity, v.cnt());
    }

    @Test
    public void test_offer_whenMany() {
        int capacity = 16;
        IOVector v = new IOVector(capacity);
        for (int k = 0; k < v.capacity(); k++) {
            IOBuffer buf = new IOBuffer(1024, true);
            buf.writeInt(10);
            buf.flip();
            assertTrue(v.offer(buf));
            Assert.assertEquals(k + 1, v.cnt());
            Assert.assertEquals((k + 1) * SIZEOF_INT, v.pending());
        }
    }

    @Test
    public void test_compact_whenNegativeWritten() {
        IOVector v = new IOVector(16);
        assertThrows(IllegalArgumentException.class, () -> v.compact(-1));
    }

    @Test
    public void test_compact_whenEverythingWritten() {
        int capacity = 16;
        IOVector v = new IOVector(capacity);
        for (int k = 0; k < v.capacity(); k++) {
            IOBuffer buf = new IOBuffer(1024, true);
            buf.writeInt(10);
            buf.flip();
            v.offer(buf);
        }

        long pending = v.pending();
        v.compact(pending);

        Assert.assertEquals(0, v.cnt());
        Assert.assertEquals(0, v.pending());
    }

    @Test
    public void test_compact_whenNothingWritten() {
        int capacity = 16;
        IOVector v = new IOVector(capacity);
        for (int k = 0; k < v.capacity(); k++) {
            IOBuffer buf = new IOBuffer(1024, true);
            buf.writeInt(10);
            buf.flip();
            v.offer(buf);
        }

        v.compact(0);

        Assert.assertEquals(16, v.cnt());
        Assert.assertEquals(16 * SIZEOF_INT, v.pending());
    }

    @Test
    public void test_compact_whenFirstItemPartiallyWritten() {
        int capacity = 4;

        IOBuffer buf1 = new IOBuffer(1024, true);
        buf1.writeInt(1);
        buf1.flip();

        IOBuffer buf2 = new IOBuffer(1024, true);
        buf2.writeInt(1);
        buf2.flip();

        IOBuffer buf3 = new IOBuffer(1024, true);
        buf3.writeInt(1);
        buf3.flip();

        IOVector v = new IOVector(capacity);
        v.offer(buf1);
        v.offer(buf2);
        v.offer(buf2);

        v.compact(1);

        Assert.assertEquals(3, v.cnt());
        Assert.assertEquals(3 * SIZEOF_INT - 1, v.pending());
        Assert.assertEquals(1, buf1.position());
        Assert.assertEquals(0, buf2.position());
        Assert.assertEquals(0, buf3.position());
    }

    @Test
    public void test_compact_whenSecondItemPartiallyWritten() {
        int capacity = 4;

        IOBuffer buf1 = new IOBuffer(1024, true);
        buf1.writeInt(1);
        buf1.flip();

        IOBuffer buf2 = new IOBuffer(1024, true);
        buf2.writeInt(1);
        buf2.flip();

        IOBuffer buf3 = new IOBuffer(1024, true);
        buf3.writeInt(1);
        buf3.flip();

        IOVector v = new IOVector(capacity);
        v.offer(buf1);
        v.offer(buf2);
        v.offer(buf2);

        v.compact(5);

        Assert.assertEquals(2, v.cnt());
        Assert.assertEquals(3 * SIZEOF_INT - 5, v.pending());
        Assert.assertEquals(1, buf2.position());
        Assert.assertEquals(0, buf3.position());
    }
}
