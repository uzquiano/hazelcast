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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Contains the metrics for a {@link TaskGroup}.
 */
public class TaskGroupMetrics {
    private static final VarHandle TASKS_PROCESSED_COUNT;
    private static final VarHandle CPU_TIME_NANOS;

    private volatile long taskCompletedCount;
    private volatile long cpuTimeNanos;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            TASKS_PROCESSED_COUNT = l.findVarHandle(TaskGroupMetrics.class, "taskCompletedCount", long.class);
            CPU_TIME_NANOS = l.findVarHandle(TaskGroupMetrics.class, "cpuTimeNanos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public long taskProcessCount() {
        return (long) TASKS_PROCESSED_COUNT.getOpaque(this);
    }

    public void incTasksProcessedCount(int delta) {
        TASKS_PROCESSED_COUNT.setOpaque(this, (long) TASKS_PROCESSED_COUNT.getOpaque(this) + delta);
    }

    public long cpuTimeNanos() {
        return (long) CPU_TIME_NANOS.getOpaque(this);
    }

    public void incCpuTimeNanos(long delta) {
        CPU_TIME_NANOS.setOpaque(this, (long) CPU_TIME_NANOS.getOpaque(this) + delta);
    }
}