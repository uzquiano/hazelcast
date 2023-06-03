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

package com.hazelcast.internal.tpc.server;

import com.hazelcast.internal.nio.Packet;
import com.hazelcast.internal.tpcengine.Eventloop;
import com.hazelcast.internal.tpcengine.Task;
import com.hazelcast.internal.tpcengine.TaskFactory;
import com.hazelcast.spi.impl.operationexecutor.impl.OperationTaskFactory;

/**
 * The TpcScheduler effectively is chain 2 schedulers:
 * - The OpScheduler for next generation operations
 * - The OperationScheduler for classic operations.
 */
public class TpcTaskFactory implements TaskFactory {

    private final CmdTaskFactory requestScheduler;
    private final OperationTaskFactory operationScheduler;

    public TpcTaskFactory(CmdTaskFactory requestScheduler, OperationTaskFactory operationScheduler) {
        this.requestScheduler = requestScheduler;
        this.operationScheduler = operationScheduler;
    }

    @Override
    public void init(Eventloop eventloop) {
        operationScheduler.init(eventloop);
        requestScheduler.init(eventloop);
    }

    @Override
    public Task toTask(Object cmd) {
        if (cmd instanceof Packet) {
           throw new UnsupportedOperationException();
            //operationScheduler.schedule(task);
        } else {
            return requestScheduler.toTask(cmd);
        }

        //return null;
    }
}