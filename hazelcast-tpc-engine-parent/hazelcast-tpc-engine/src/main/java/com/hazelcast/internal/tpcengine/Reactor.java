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


import com.hazelcast.internal.tpcengine.file.AsyncFile;
import com.hazelcast.internal.tpcengine.file.StorageDeviceRegistry;
import com.hazelcast.internal.tpcengine.logging.TpcLogger;
import com.hazelcast.internal.tpcengine.logging.TpcLoggerLocator;
import com.hazelcast.internal.tpcengine.net.AbstractAsyncSocket.AcceptRequest;
import com.hazelcast.internal.tpcengine.net.AsyncServerSocket;
import com.hazelcast.internal.tpcengine.net.AsyncSocket;
import com.hazelcast.internal.tpcengine.nio.NioReactor;
import com.hazelcast.internal.tpcengine.util.AbstractBuilder;
import com.hazelcast.internal.tpcengine.util.CircularQueue;
import com.hazelcast.internal.tpcengine.util.EpochClock;
import com.hazelcast.internal.util.ThreadAffinity;
import com.hazelcast.internal.util.ThreadAffinityHelper;
import org.jctools.queues.MpscArrayQueue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.BitSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import static com.hazelcast.internal.tpcengine.Reactor.State.NEW;
import static com.hazelcast.internal.tpcengine.Reactor.State.RUNNING;
import static com.hazelcast.internal.tpcengine.Reactor.State.SHUTDOWN;
import static com.hazelcast.internal.tpcengine.Reactor.State.TERMINATED;
import static com.hazelcast.internal.tpcengine.util.Preconditions.checkNotNull;
import static com.hazelcast.internal.tpcengine.util.Preconditions.checkPositive;
import static java.lang.System.getProperty;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

/**
 * A Reactor is an implementation of the reactor design pattern. So it listen to
 * some event sources and then dispatches the events to the appropriate handler.
 * This is coordinated from the {@link Eventloop} that is inside each reactor.
 * <p/>
 * There are various forms of events:
 * <ol>
 *     <li>Outside tasks: tasks are offered outside of the eventloop</li>
 *     <li>Inside tasks: tasks that are offered within the eventloop</li>
 *     <li>Deadline tasks: tasks that have been scheduled by the Reactor</li>
 *     <li>Tasks from some asynchronous eventing system that interacts with I/O
 *     like the {@link com.hazelcast.internal.tpcengine.file.AsyncFile},
 *     {@link AsyncServerSocket} and {@link AsyncSocket}.</li>>
 * </ol>
 * <p/>
 * A single Reactor typically will process one or more {@link AsyncServerSocket}
 * instances and many {@link AsyncSocket} instances. A single reactor can even run
 * the {@link AsyncServerSocket} and the {@link AsyncSocket} that initiates the
 * call the the {@link AsyncSocket} created by the {@link AsyncServerSocket}.
 * <p/>
 * A single Reactor can also serve many {@link com.hazelcast.internal.tpcengine.file.AsyncFile}
 * instances.
 */
@SuppressWarnings({"checkstyle:DeclarationOrder", "checkstyle:VisibilityModifier", "rawtypes"})
public abstract class Reactor implements Executor {

    protected static final AtomicReferenceFieldUpdater<Reactor, State> STATE
            = newUpdater(Reactor.class, State.class, "state");

    protected final ConcurrentMap<?, ?> context = new ConcurrentHashMap<>();
    protected final TpcLogger logger = TpcLoggerLocator.getLogger(getClass());
    protected final TaskQueue defaultTaskQueue;
    protected final Eventloop eventloop;
    protected final boolean spin;
    protected final Thread eventloopThread;
    protected final String name;
    protected final AtomicBoolean wakeupNeeded;
    private final TpcEngine engine;
    private final ReactorType type;
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private final CountDownLatch startLatch = new CountDownLatch(1);
    private final Consumer<Reactor> initFn;
    private final ReactorResources<AsyncSocket> sockets = new ReactorResources<>();
    private final ReactorResources<AsyncServerSocket> serverSockets = new ReactorResources<>();
    private final ReactorResources<AsyncFile> files = new ReactorResources<>();

    @SuppressWarnings("java:S1845")
    protected volatile State state = NEW;
    protected final Metrics metrics = new Metrics();

    /**
     * Creates a new {@link Reactor}.
     *
     * @param builder the {@link Builder}.
     */
    protected Reactor(Builder builder) {
        this.type = builder.type;
        this.spin = builder.spin;
        this.engine = builder.engine;
        this.initFn = builder.initFn;
        CompletableFuture<Eventloop> eventloopFuture = new CompletableFuture<>();
        this.eventloopThread = builder.threadFactory.newThread(new StartEventloopTask(eventloopFuture, builder));

        if (builder.threadName != null) {
            eventloopThread.setName(builder.threadName);
        }
        this.name = builder.reactorName;

        // The eventloopThread is started so eventloop gets created on the eventloop thread.
        // but the actual processing of the eventloop is only done after start() is called.
        eventloopThread.start();

        // wait for the eventloop to be created.
        eventloop = eventloopFuture.join();
        // There is a happens-before edge between writing to the eventloopFuture and
        // the join. So at this point we can safely read the fields that have been
        // set in the constructor of the eventloop.
        this.defaultTaskQueue = eventloop.defaultTaskQueueHandle.queue;
        this.wakeupNeeded = eventloop.wakeupNeeded;
    }

    /**
     * Gets the sockets that belong to this Reactor.
     * <p/>
     * This method is threadsafe.
     *
     * @return the async sockets that belong to this Reactor.
     */
    public final ReactorResources<AsyncSocket> sockets() {
        return sockets;
    }

    /**
     * Gets all the AsyncServerSockets that belong to this Reactor.
     * <p/>
     * This method is threadsafe.
     *
     * @return the AsyncServerSockets that belong to this Reactor.
     */
    public final ReactorResources<AsyncServerSocket> serverSockets() {
        return serverSockets;
    }

    /**
     * Gets all the AsyncFiles that belong to this Reactor.
     * <p/>
     * This method is threadsafe.
     *
     * @return the AsyncFiles that belong to this Reactors.
     */
    public final ReactorResources<AsyncFile> files() {
        return files;
    }

    /**
     * Allows for objects to be bound to this Reactor. Useful for the lookup
     * of services and other dependencies.
     * <p/>
     * This method is thread-safe and can be called independent of the state of the Reactor.
     */
    public final ConcurrentMap<?, ?> context() {
        return context;
    }

    /**
     * Returns the {@link Metrics} of this reactor.
     * <p/>
     * This method is thread-safe.
     *
     * @return the {@link Metrics}. Thee returned value will always be a
     * valid {@link Metrics} instance independent of the state of the reactor.
     */
    public final Metrics metrics() {
        return metrics;
    }

    /**
     * Gets the name of this reactor. Useful for debugging purposes.
     * <p/>
     * This method is thread-safe.
     *
     * @return the name.
     */
    public final String name() {
        return name;
    }

    /**
     * Returns the {@link ReactorType} of this {@link Reactor}.
     * <p/>
     * This method is thread-safe.
     *
     * @return the {@link ReactorType} of this {@link Reactor}. Value will
     * never be null.
     */
    public final ReactorType type() {
        return type;
    }

    /**
     * Returns the Eventloop for this {@link Reactor}.
     * <p/>
     * This method is thread-safe. But the Eventloop should only be touched
     * by the Eventloop thread because the Eventloop is not thread-safe.
     *
     * @return the {@link Eventloop}.
     */
    public final Eventloop eventloop() {
        return eventloop;
    }

    /**
     * Returns the {@link Thread} that runs the eventloop. The eventloop thread
     * is created when the Reactor is created.
     * <p/>
     * This method is thread-safe.
     *
     * @return the thread running the eventloop.
     */
    public final Thread eventloopThread() {
        return eventloopThread;
    }

    /**
     * Returns the {@link State} of the Reactor.
     * <p/>
     * This method is thread-safe.
     *
     * @return the state.
     */
    public final State state() {
        return state;
    }

    /**
     * Creates the Eventloop run by this Reactor. Will be called from the
     * eventloop-thread.
     *
     * @return the created Eventloop instance.
     */
    protected abstract Eventloop newEventloop(Builder builder);

    /**
     * Creates a new {@link AsyncServerSocket.Builder}.
     * <p/>
     * This method is thread-safe.
     *
     * @return the created builder.
     * @throws IllegalStateException if the reactor isn't running.
     */
    public abstract AsyncSocket.Builder newAsyncSocketBuilder();

    /**
     * Creates a new {@link AsyncServerSocket.Builder} for the given
     * acceptRequest.
     * <p/>
     * This method is thread-safe.
     *
     * @param acceptRequest a wrapper around a lower level socket implemented
     *                      that needs to be accepted.
     * @return the created builder.
     * @throws NullPointerException  if acceptRequest is null.
     * @throws IllegalStateException if the reactor isn't running.
     */
    public abstract AsyncSocket.Builder newAsyncSocketBuilder(AcceptRequest acceptRequest);

    /**
     * Creates a new builder for an AsyncServerSocket.
     * <p/>
     * This method is thread-safe.
     *
     * @return the created builder.
     * @throws IllegalStateException if the reactor isn't running.
     */
    public abstract AsyncServerSocket.Builder newAsyncServerSocketBuilder();

    protected void verifyRunning() {
        State state0 = state;
        if (RUNNING != state0) {
            throw new IllegalStateException("Reactor not in RUNNING state, but " + state0);
        }
    }

    /**
     * Starts the reactor.
     * <p/>
     * This method is thread-safe.
     *
     * @throws IllegalStateException if the reactor isn't in NEW state.
     */
    public Reactor start() {
        if (!STATE.compareAndSet(Reactor.this, NEW, RUNNING)) {
            throw new IllegalStateException("Can't start reactor, invalid state:" + state);
        }
        startLatch.countDown();
        return this;
    }

    /**
     * Shuts down the Reactor.
     * <p/>
     * This call doesn't wait for the Reactor to shut down. The
     * {@link #awaitTermination(long, TimeUnit)} should be used for that.
     * <p/>
     * This call can safely be made no matter the state of the Reactor.
     * <p/>
     * This method is thread-safe.
     */
    public final void shutdown() {
        for (; ; ) {
            State oldState = state;
            switch (oldState) {
                case NEW:
                    if (STATE.compareAndSet(this, oldState, TERMINATED)) {
                        // the eventloop thread is waiting on the startLatch,
                        // so we need to wake it up. It will then check the
                        // status and terminate if needed.
                        startLatch.countDown();
                        return;
                    }

                    break;
                case RUNNING:
                    if (STATE.compareAndSet(this, oldState, SHUTDOWN)) {
                        submit(() -> eventloop.stop = true);
                        return;
                    }
                    break;
                default:
                    return;
            }
        }
    }

    /**
     * Awaits for the termination of the Reactor with the given timeout.
     * <p/>
     * This method is thread-safe.
     *
     * @param timeout the timeout
     * @param unit    the TimeUnit
     * @return true if the Reactor is terminated.
     * @throws InterruptedException if the thread was interrupted while waiting.
     */
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (!terminationLatch.await(timeout, unit)) {
            logger.warning("Termination latch timed out.");
        }

        return state == TERMINATED;
    }

    /**
     * Wakes up the {@link Reactor} when it is blocked and needs to be woken up
     * because there is work that requires attention.
     * <p/>
     * This method is thread-safe.
     */
    public abstract void wakeup();

    // todo: task queue id?
    @Override
    public void execute(Runnable command) {
        if (!offer(command)) {
            throw new RejectedExecutionException("Task " + command.toString()
                    + " rejected from " + this);
        }
    }

    /**
     * Executes a Callable on the Reactor and returns a CompletableFuture with
     * its content.
     * <p/>
     * Warning: This method is very inefficient because it creates a lot of
     * litter. It should not be run too frequent because performance will tank.
     *
     * @param callable
     * @param <E>
     * @return
     */
    public final <E> CompletableFuture<E> submit(Callable<E> callable) {
        CompletableFuture future = new CompletableFuture();
        Runnable task = () -> {
            try {
                future.complete(callable.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };

        if (!offer(task)) {
            future.completeExceptionally(new RejectedExecutionException("Task " + callable.toString()
                    + " rejected from " + this));
        }

        return future;
    }

    /**
     * Executes a Callable on the Reactor and returns a CompletableFuture with
     * its content.
     * <p/>
     * Warning: This method is very inefficient because it creates a lot of litter.
     * It should not be run too frequent because performance will tank.
     *
     * @param cmd
     * @return a CompletableFuture.
     */
    public final CompletableFuture<Void> submit(Runnable cmd) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                cmd.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };

        if (!offer(task)) {
            future.completeExceptionally(new RejectedExecutionException("Task " + cmd.toString()
                    + " rejected from " + this));
        }

        return future;
    }


    /**
     * Offers a task to be executed on this {@link Reactor}.
     * <p/>
     * This method is thread-safe.
     *
     * @param task the task to execute.
     * @return true if the task was accepted, false otherwise.
     * @throws NullPointerException if task is null.
     */
    public final boolean offer(Runnable task) {
        return offer(task, eventloop.defaultTaskQueueHandle);
    }

    /**
     * Offers a task to be executed on this {@link Reactor}.
     * <p/>
     * This method is thread-safe.
     *
     * @param task the task to execute.
     * @return true if the task was accepted, false otherwise.
     * @throws NullPointerException if task is null.
     */
    public final boolean offer(Object task) {
        return offer(task, eventloop.defaultTaskQueueHandle);
    }

    public final boolean offer(Object task, TaskQueue.Handle handle) {
        checkNotNull(task, "task");
        checkNotNull(handle, "handle");

        //todo: is running
        if (handle.queue.eventloop != eventloop) {
            throw new IllegalArgumentException();
        }

        if (Thread.currentThread() == eventloopThread) {
            // todo: only set when there is a inside queue
            return handle.queue.offerInside(task);
        } else if (handle.queue.offerOutside(task)) {
            wakeup();
            return true;
        } else {
            return false;
        }
    }

    @Override
    public final String toString() {
        return name;
    }

    /**
     * The state of the {@link Reactor}.
     */
    public enum State {
        NEW,
        RUNNING,
        SHUTDOWN,
        TERMINATED
    }

    /**
     * The StartEventloopTask does a few important things:
     * <ol>
     *     <li>Configure the thread affinity</li>
     *     <li>Create the eventloop</li>
     *     <li>Run the eventloop</li>
     *     <li>Manage the lifecycle of the reactor when it terminates.</li>
     * </ol>
     */
    private final class StartEventloopTask implements Runnable {
        private final CompletableFuture<Eventloop> future;
        private final Builder reactorBuilder;

        private StartEventloopTask(CompletableFuture<Eventloop> future,
                                   Builder reactorBuilder) {
            this.future = future;
            this.reactorBuilder = reactorBuilder;
        }

        @SuppressWarnings({"java:S1181", "java:S1141"})
        @Override
        public void run() {
            try {
                try {
                    configureThreadAffinity();
                    Eventloop eventloop0 = newEventloop(reactorBuilder);
                    future.complete(eventloop0);

                    startLatch.await();
                    try {
                        // it could be that the thread wakes up due to termination.
                        // So we need to check the state first before running.
                        if (state == RUNNING) {
                            eventloop0.beforeRun();

                            if (initFn != null) {
                                initFn.accept(Reactor.this);
                            }

                            eventloop0.run();
                        }
                    } finally {
                        eventloop0.destroy();
                    }
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                    logger.severe(e);
                } finally {
                    state = TERMINATED;

                    terminationLatch.countDown();

                    if (engine != null) {
                        engine.notifyReactorTerminated();
                    }

                    if (logger.isInfoEnabled()) {
                        logger.info(Thread.currentThread().getName() + " terminated.");
                    }
                }
            } catch (Throwable e) {
                // log whatever wasn't caught so that we don't swallow throwables.
                logger.severe(e);
            }
        }

        private void configureThreadAffinity() {
            ThreadAffinity threadAffinity = reactorBuilder.threadAffinity;
            BitSet allowedCpus = threadAffinity == null ? null : threadAffinity.nextAllowedCpus();
            if (allowedCpus == null) {
                return;
            }

            ThreadAffinityHelper.setAffinity(allowedCpus);
            BitSet actualCpus = ThreadAffinityHelper.getAffinity();
            if (actualCpus.equals(allowedCpus)) {
                if (logger.isFineEnabled()) {
                    logger.fine(Thread.currentThread().getName() + " has affinity for CPUs:" + allowedCpus);
                }
            } else {
                logger.warning(Thread.currentThread().getName() + " affinity was not applied successfully. "
                        + "Expected CPUs:" + allowedCpus + ". Actual CPUs:" + actualCpus);
            }
        }
    }

    /**
     * Contains metrics for a {@link Reactor}.
     * <p/>
     * The metrics should only be updated by the eventloop thread, but can be read
     * by any thread.
     */
    public static final class Metrics {

        private static final VarHandle TASKS_PROCESSED_COUNT;
        private static final VarHandle CPU_TIME_NANOS;
        private static final VarHandle CONTEXT_SWITCH_COUNT;

        private volatile long taskCompletedCount;
        private volatile long cpuTimeNanos;
        private volatile long contextSwitchCount;
        private final long startTimeNanos = EpochClock.INSTANCE.epochNanos();

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                TASKS_PROCESSED_COUNT = l.findVarHandle(Metrics.class, "taskCompletedCount", long.class);
                CPU_TIME_NANOS = l.findVarHandle(Metrics.class, "cpuTimeNanos", long.class);
                CONTEXT_SWITCH_COUNT = l.findVarHandle(Metrics.class, "contextSwitchCount", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public long startTimeNanos() {
            return startTimeNanos;
        }

        /**
         * Returns the number of tasks that have run on the Reactor.
         *
         * @return the number of tasks.
         */
        public long taskProcessCount() {
            return (long) TASKS_PROCESSED_COUNT.getOpaque(this);
        }

        /**
         * Increases the number of tasks that have run on the Reactor.
         *
         * @param delta the increment
         */
        public void incTasksProcessedCount(int delta) {
            TASKS_PROCESSED_COUNT.setOpaque(this, (long) TASKS_PROCESSED_COUNT.getOpaque(this) + delta);
        }

        public long cpuTimeNanos() {
            return (long) CPU_TIME_NANOS.getOpaque(this);
        }

        public void incCpuTimeNanos(long delta) {
            CPU_TIME_NANOS.setOpaque(this, (long) CPU_TIME_NANOS.getOpaque(this) + delta);
        }

        /**
         * Returns the number of task context switches the Reactor has performed.
         *
         * @return the number of task context switches.
         */
        public long contextSwitchCount() {
            return (long) CONTEXT_SWITCH_COUNT.getOpaque(this);
        }

        /**
         * Increases the number of task context switches the Reactor has performed by 1.
         */
        public void incContextSwitchCount() {
            CONTEXT_SWITCH_COUNT.setOpaque(this, (long) CONTEXT_SWITCH_COUNT.getOpaque(this) + 1);
        }
    }

    /**
     * A Builder for {@link Reactor} instances.
     */
    @SuppressWarnings({"checkstyle:VisibilityModifier"})
    public abstract static class Builder extends AbstractBuilder<Reactor> {

        public static final String NAME_SCHEDULED_RUN_QUEUE_CAPACITY
                = "hazelcast.tpc.reactor.deadlineRunQueue.capacity";
        public static final String NAME_RUN_QUEUE_CAPACITY
                = "hazelcast.tpc.reactor.runQueue.capacity";
        public static final String NAME_TARGET_LATENCY_NANOS
                = "hazelcast.tpc.reactor.targetLatency.ns";
        public static final String NAME_MIN_GRANULARITY_NANOS
                = "hazelcast.tpc.reactor.minGranularity.ns";
        public static final String NAME_STALL_THRESHOLD_NANOS
                = "hazelcast.tpc.reactor.stallThreshold.ns";
        public static final String NAME_IO_INTERVAL_NANOS
                = "hazelcast.tpc.reactor.ioInterval.ns";
        public static final String NAME_MAX_SOCKETS
                = "hazelcast.tpc.reactor.maxSockets";
        public static final String NAME_CFS
                = "hazelcast.tpc.reactor.cfs";
        public static final String NAME_REACTOR_AFFINITY
                = "hazelcast.tpc.reactor.affinity";
        public static final String NAME_REACTOR_SPIN
                = "hazelcast.tpc.reactor.spin";

        private static final AtomicInteger THREAD_ID_GENERATOR = new AtomicInteger();
        private static final AtomicInteger REACTOR_ID_GENERATOR = new AtomicInteger();
        private static final int DEFAULT_INSIDE_TASK_QUEUE_CAPACITY = 65536;
        private static final int DEFAULT_OUTSIDE_TASK_QUEUE_CAPACITY = 65536;
        private static final int DEFAULT_SCHEDULED_RUN_QUEUE_CAPACITY = 4096;
        private static final int DEFAULT_RUN_QUEUE_CAPACITY = 1024;
        private static final long DEFAULT_STALL_THRESHOLD_NANOS = MICROSECONDS.toNanos(500);
        private static final long DEFAULT_IO_INTERVAL_NANOS = MICROSECONDS.toNanos(50);
        private static final long DEFAULT_TARGET_LATENCY_NANOS = MILLISECONDS.toNanos(1);
        private static final long DEFAULT_MIN_GRANULARITY_NANOS = MICROSECONDS.toNanos(100);
        private static final int DEFAULT_MAX_SOCKETS = 16384;
        private static final boolean DEFAULT_CFS = true;
        private static final boolean DEFAULT_SPIN = false;

        private static final ThreadAffinity DEFAULT_THREAD_AFFINITY
                = ThreadAffinity.newSystemThreadAffinity(NAME_REACTOR_AFFINITY);

        private static final Constructor<Builder> IO_URING_REACTOR_BUILDER_CONSTRUCTOR;

        private static final String IOURING_IOURING_REACTOR_BUILDER_CLASS_NAME
                = "com.hazelcast.internal.tpcengine.iouring.IOUringReactor$Builder";

        private static final ThreadFactory DEFAULT_THREAD_FACTORY = r -> {
            Thread thread = new Thread(r);
            thread.setName("ReactorThread-" + THREAD_ID_GENERATOR.getAndIncrement());
            return thread;
        };

        static {
            Constructor<Builder> constructor = null;
            try {
                Class clazz = Builder.class.getClassLoader().loadClass(
                        IOURING_IOURING_REACTOR_BUILDER_CLASS_NAME);
                constructor = clazz.getConstructor();
            } catch (ClassNotFoundException e) {
                constructor = null;
            } catch (NoSuchMethodException e) {
                throw new Error(e);
            } finally {
                IO_URING_REACTOR_BUILDER_CONSTRUCTOR = constructor;
            }
        }

        public StorageDeviceRegistry storageDeviceRegistry;

        public final ReactorType type;

        //  Sets the maximum number of {@link AsyncSocket} supported by this Reactor.
        public int maxSockets;

        /**
         * Sets the {@link ThreadAffinity}. If the threadAffinity is <code>null</code>,
         * no thread affinity is applied.
         */
        public ThreadAffinity threadAffinity;

        /**
         * Sets the ThreadFactory used to create the Thread that runs the {@link Reactor}.
         */
        public ThreadFactory threadFactory = DEFAULT_THREAD_FACTORY;

        /**
         * Sets the name of the thread. If configured, the thread name is set
         * after the thread is created. If not configured, the thread name provided
         * by the ThreadFactory is used.
         */
        public String threadName;

        /**
         * Sets the spin policy. If spin is true, the reactor will spin on the run queue if there are no
         * tasks to run. If spin is false, the reactor will park the thread if there are no tasks to run.
         * <p/>
         * In the future we want to have better policies than only spinning. For example, see
         * BackoffIdleStrategy
         */
        public boolean spin;

        /**
         * Sets the capacity of the run queue for the deadline scheduler.
         */
        public int deadlineRunQueueCapacity;

        public TpcEngine engine;

        /**
         * The maximum amount of time a task is allowed to run before being considered stalling
         * the reactor.
         * <p/>
         * Setting this value too low will lead to a lot of noise (false positives). Setting
         * this value too high will lead to not detecting the stalls on the reactor (false
         * negatives).
         */
        public long stallThresholdNanos;

        /**
         * Sets the {@link StallHandler}.
         */
        public StallHandler stallHandler;

        /**
         * The interval the I/O scheduler is be checked if there if any I/O activity (either
         * submitting work or there are any completed events.
         * <p/>
         * There is no guarantee that the I/O scheduler is going to be called at the exact interval
         * when there are other threads/processes contending for the core and when there are stalls
         * on the reactor.
         * <p/>
         * Setting the value too low will cause a lot of overhead. It can even lead to the eventloop
         * spinning on ticks to the io-scheduler instead of able to park. Setting it too high will
         * suboptimal performance in the I/O system because I/O requests will be delayed.
         */
        public long ioIntervalNanos;

        /**
         * Sets the capacity for the run queue of the {@link TaskQueueScheduler}.This defines
         * the maximum number of TaskQueues that can be created within an {@link Eventloop}.
         */
        public int runQueueCapacity;


        /**
         * Sets the total amount of time that can be divided over the taskqueues in the
         * {@link TaskQueueScheduler}. It depends on the scheduler implementation how
         * this is interpreted.
         */
        public long targetLatencyNanos;

        /**
         * Sets the minimum amount of time a taskqueue is guaranteed to run (unless the
         * taskgroup decided to stop/yield).
         * <p>
         * Setting this value too low could lead to excessive context switching. Setting
         * this value too high could lead to unresponsiveness (increased latency).
         */
        public long minGranularityNanos;

        /**
         * The scheduler to use. If cfs is true, the {@link CfsTaskQueueScheduler}
         * it used. Otherwise the {@link FcfsTaskQueueScheduler} is used. The primary
         * reason to set cfs=false is for performance testing and debugging purposes.
         */
        public boolean cfs;

        public TaskQueue.Builder defaultTaskQueueBuilder;
        /**
         * A function that is executed on the eventloop as soon as the eventloop
         * is starting.
         * <p>
         * This can be used to start tasks like opening server sockets, further
         * customizing etc. This can also be used as an alternative to having a
         * {@link TaskQueue} with a outside queue (which causes a bit of overhead
         * due to checking this thread-safe queue and repeated registration of
         * the queue in the outside-queue of the reactor).
         */
        public Consumer<Reactor> initFn;

        /**
         * The name of the reactor. Useful for logging/debugging purposes.
         */
        public String reactorName;

        protected Builder(ReactorType type) {
            this.type = checkNotNull(type);
            this.deadlineRunQueueCapacity = Integer.getInteger(
                    NAME_SCHEDULED_RUN_QUEUE_CAPACITY,
                    DEFAULT_SCHEDULED_RUN_QUEUE_CAPACITY);
            this.runQueueCapacity = Integer.getInteger(NAME_RUN_QUEUE_CAPACITY, DEFAULT_RUN_QUEUE_CAPACITY);
            this.targetLatencyNanos = Long.getLong(NAME_TARGET_LATENCY_NANOS, DEFAULT_TARGET_LATENCY_NANOS);
            this.minGranularityNanos = Long.getLong(NAME_MIN_GRANULARITY_NANOS, DEFAULT_MIN_GRANULARITY_NANOS);
            this.stallThresholdNanos = Long.getLong(NAME_STALL_THRESHOLD_NANOS, DEFAULT_STALL_THRESHOLD_NANOS);
            this.ioIntervalNanos = Long.getLong(NAME_IO_INTERVAL_NANOS, DEFAULT_IO_INTERVAL_NANOS);
            this.spin = Boolean.parseBoolean(getProperty(NAME_REACTOR_SPIN, Boolean.toString(DEFAULT_SPIN)));
            this.cfs = Boolean.parseBoolean(getProperty(NAME_CFS, Boolean.toString(DEFAULT_CFS)));
            this.threadAffinity = DEFAULT_THREAD_AFFINITY;
            this.maxSockets = Integer.getInteger(NAME_MAX_SOCKETS, DEFAULT_MAX_SOCKETS);
        }

        /**
         * Creates a new {@link Builder} based on the {@link ReactorType}.
         *
         * @param type the reactor type.
         * @return the created builder.
         * @throws NullPointerException if type is null.
         * @throws RuntimeException     if the IO_URING reactor is requested but the class is
         *                              not found or there are other problems.
         */
        public static Builder newReactorBuilder(ReactorType type) {
            checkNotNull(type, "type");

            switch (type) {
                case NIO:
                    return new NioReactor.Builder();
                case IOURING:
                    if (IO_URING_REACTOR_BUILDER_CONSTRUCTOR == null) {
                        throw new IllegalStateException(
                                "class " + IOURING_IOURING_REACTOR_BUILDER_CLASS_NAME + " is not found");
                    }

                    try {
                        return IO_URING_REACTOR_BUILDER_CONSTRUCTOR.newInstance();
                    } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                default:
                    throw new IllegalStateException("Unknown reactorType: " + type);
            }
        }

        @Override
        protected void conclude() {
            super.conclude();

            checkPositive(maxSockets, "maxSockets");
            checkPositive(runQueueCapacity, "runQueueCapacity");
            checkPositive(targetLatencyNanos, "targetLatencyNanos");
            checkPositive(minGranularityNanos, "minGranularityNanos");
            checkPositive(stallThresholdNanos, "stallThresholdNanos");
            checkPositive(ioIntervalNanos, "ioIntervalNanos");
            checkPositive(deadlineRunQueueCapacity, "deadlineRunQueueCapacity");

            if (storageDeviceRegistry == null) {
                storageDeviceRegistry = new StorageDeviceRegistry();
            }

            if (reactorName == null) {
                reactorName = "Reactor-" + REACTOR_ID_GENERATOR.getAndIncrement();
            }

            if (defaultTaskQueueBuilder == null) {
                defaultTaskQueueBuilder = new TaskQueue.Builder();
                defaultTaskQueueBuilder.name = "default";
                defaultTaskQueueBuilder.outside = new MpscArrayQueue<>(DEFAULT_OUTSIDE_TASK_QUEUE_CAPACITY);
                defaultTaskQueueBuilder.inside = new CircularQueue<>(DEFAULT_INSIDE_TASK_QUEUE_CAPACITY);
            }

            if (stallHandler == null) {
                stallHandler = LoggingStallHandler.INSTANCE;
            }
        }
    }
}