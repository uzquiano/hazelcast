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

package com.hazelcast.internal.tpcengine.file;

import com.hazelcast.internal.tpcengine.Reactor;
import com.hazelcast.internal.tpcengine.util.BufferUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static com.hazelcast.internal.tpcengine.TpcTestSupport.assertSuccessEventually;
import static com.hazelcast.internal.tpcengine.file.AsyncFile.O_CREAT;
import static com.hazelcast.internal.tpcengine.file.AsyncFile.O_RDONLY;
import static com.hazelcast.internal.tpcengine.file.AsyncFile.O_WRONLY;
import static com.hazelcast.internal.tpcengine.file.AsyncFile.PERMISSIONS_ALL;
import static com.hazelcast.internal.tpcengine.file.FileTestSupport.assertSameContent;
import static com.hazelcast.internal.tpcengine.file.FileTestSupport.randomTmpFile;
import static com.hazelcast.internal.tpcengine.util.BufferUtil.allocateDirect;
import static com.hazelcast.internal.tpcengine.util.OS.pageSize;

public abstract class FileCopyTest {
    private Reactor reactor;

    public abstract Reactor newReactor();

    @Before
    public void before() {
        reactor = newReactor();
        reactor.start();
    }

    @After
    public void after() {
        if (reactor != null) {
            reactor.shutdown();
        }
    }

    @Test
    public void test_1B() {
        run(1);
    }

    @Test
    public void test_2B() {
        run(2);
    }

    @Test
    public void test_1KB() {
        run(1024);
    }

    @Test
    public void test_2KB() {
        run(2048);
    }

    @Test
    public void test_4KB() {
        run(4096);
    }

    @Test
    public void test_8KB() {
        run(8192);
    }

    @Test
    public void test_64KB() {
        run(64 * 1024);
    }

    @Test
    public void test_128KB() {
        run(128 * 1024);
    }

    @Test
    public void test_256KB() {
        run(256 * 1024);
    }

    @Test
    public void test_512KB() {
        run(512 * 1024);
    }

    @Test
    public void test_1MB() {
        run(1024 * 1024);
    }

    @Test
    public void test_2MB() {
        run(2 * 1024 * 1024);
    }

    @Test
    public void test_4MB() {
        run(4 * 1024 * 1024);
    }

    public void run(int size) {
        File srcTmpFile = randomTmpFile(size);
        File dstTmpFile = randomTmpFile();

        CompletableFuture future = new CompletableFuture();

        Runnable task = () -> {
            AsyncFile src = reactor.eventloop().newAsyncFile(srcTmpFile.getAbsolutePath());
            AsyncFile dst = reactor.eventloop().newAsyncFile(dstTmpFile.getAbsolutePath());

            src.open(O_RDONLY, PERMISSIONS_ALL).then((r1, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                }

                dst.open(O_WRONLY | O_CREAT, PERMISSIONS_ALL).then((r2, throwable2) -> {
                    if (throwable2 != null) {
                        future.completeExceptionally(throwable2);
                    }

                    reactor.execute(new CopyFileTask(src, dst, future));
                });
            });

        };
        reactor.offer(task);

        assertSuccessEventually(future);
        assertSameContent(srcTmpFile, dstTmpFile);
    }

    private class CopyFileTask implements Runnable, BiConsumer<Integer, Throwable> {
        private final ByteBuffer buffer;
        private final long bufferAddress;
        private final CompletableFuture future;
        private int block;
        private long blockCount;
        private int bytesToWrite;
        private long bytesWritten;
        private final AsyncFile src;
        private final AsyncFile dst;
        private boolean read;

        private CopyFileTask(AsyncFile src, AsyncFile dst, CompletableFuture future) {
            this.blockCount = src.size();
            this.src = src;
            this.dst = dst;
            this.future = future;
            // Setting up the buffer
            this.buffer = allocateDirect(pageSize(), pageSize());
            this.bufferAddress = BufferUtil.addressOf(buffer);
        }

        @Override
        public void run() {
            if (read) {
                src.pread(block * pageSize(), pageSize(), bufferAddress).then(this);
            } else {
                //
                dst.pwrite(bytesWritten, bytesToWrite, bufferAddress).then(this);
            }
        }

        @Override
        public void accept(Integer integer, Throwable throwable) {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                return;
            }

            if (read) {
                read = false;
                bytesToWrite = integer;
                run();
            } else {
                read = true;
                bytesWritten += integer;
                // if we are at the end
                if (bytesWritten == src.size()) {
                    dst.close().then(new BiConsumer<Integer, Throwable>() {
                        @Override
                        public void accept(Integer integer, Throwable throwable) {
                            if (throwable != null) {
                                future.completeExceptionally(throwable);
                                return;
                            }

                            future.complete(null);
                        }
                    });

                    return;
                }
                run();
                block++;
            }
        }
    }
}