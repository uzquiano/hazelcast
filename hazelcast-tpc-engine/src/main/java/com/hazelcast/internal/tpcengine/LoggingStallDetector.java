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

package com.hazelcast.internal.tpcengine;

import com.hazelcast.internal.tpcengine.logging.TpcLogger;
import com.hazelcast.internal.tpcengine.logging.TpcLoggerLocator;

/**
 * A {@link StallDetector} that writes a log entry when a stall is detected.
 */
public class LoggingStallDetector implements StallDetector {
    public final static LoggingStallDetector INSTANCE = new LoggingStallDetector();

    protected final TpcLogger logger = TpcLoggerLocator.getLogger(getClass());

    @Override
    public void onStall(Reactor reactor, Runnable task, long startNanos, long durationNanos) {
        if (logger.isSevereEnabled()) {
            logger.severe(reactor + " detected stall of " + durationNanos + " ns, the culprit is " + task);
        }
    }
}