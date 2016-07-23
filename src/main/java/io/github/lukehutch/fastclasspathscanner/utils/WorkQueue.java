package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

public abstract class WorkQueue<T> implements AutoCloseable {
    private final BlockingQueue<T> workQueue;
    private final AtomicInteger numWorkUnitsRemaining = new AtomicInteger();
    private final AtomicBoolean stopAllWorkers = new AtomicBoolean();
    private final BlockingQueue<Future<Void>> workerFutures = new LinkedBlockingQueue<>();
    protected final ThreadLog log;

    public WorkQueue(final ThreadLog log) {
        this.workQueue = createQueue();
        this.log = log;
    }

    /** Start worker threads with a shared log. */
    public void startWorkers(final ExecutorService executorService, final int numWorkers, final ThreadLog log) {
        for (int i = 0; i < numWorkers; i++) {
            workerFutures.add(executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    runWorkLoop();
                    return null;
                }
            }));
        }
    }

    /** Start worker threads, each with their own log, which is flushed when the worker exits. */
    public void startWorkers(final ExecutorService executorService, final int numWorkers) {
        for (int i = 0; i < numWorkers; i++) {
            workerFutures.add(executorService.submit(new LoggedThread<Void>() {
                @Override
                public Void doWork() throws Exception {
                    runWorkLoop();
                    return null;
                }
            }));
        }
    }

    /**
     * Returns true if this thread or any other thread has been interrupted. If this thread has been interrupted,
     * interrupts other threads. If no thread has been interrupted, returns false.
     */
    public boolean interrupted() {
        if (Thread.currentThread().isInterrupted()) {
            stopAllWorkers.set(true);
        }
        if (stopAllWorkers.get()) {
            return true;
        }
        return false;
    }

    /**
     * Start a worker. Called by startWorkers(), but should also be called by the main thread to do some of the work
     * on that thread, to prevent deadlock in the case that the ExecutorService doesn't have as many threads
     * available as numParallelTasks. When this method returns, either all the work has been completed, or this or
     * some other thread was interrupted. If InterruptedException is thrown, this thread or another was interrupted.
     */
    public void runWorkLoop() {
        // Get next work unit from queue
        while (numWorkUnitsRemaining.get() > 0) {
            T workUnit = null;
            while (numWorkUnitsRemaining.get() > 0) {
                // Check for interruption
                if (interrupted()) {
                    return;
                }
                // Busy-wait for work units added after the queue is empty, while work units are still
                // being processed, since the in-process work units may generate other work units.
                workUnit = workQueue.poll();
                if (workUnit != null) {
                    // Got a work unit
                    break;
                }
            }
            if (workUnit == null) {
                // No work units remaining
                return;
            }
            // Got a work unit -- hold numWorkUnitsRemaining high until work is complete
            try {
                // Process the work unit
                processWorkUnit(workUnit);
            } catch (final InterruptedException e) {
                stopAllWorkers.set(true);
                return;
            } catch (final Exception e) {
                if (FastClasspathScanner.verbose) {
                    log.log("Exception in worker thread", e);
                }
                throw e;
            } finally {
                // Only after completing the work unit, decrement the count of work units remaining.
                // This way, if process() generates mork work units, but the queue is emptied some time
                // after this work unit was removed from the queue, other worker threads haven't
                // terminated yet, so the newly-added work units can get taken by workers.  
                numWorkUnitsRemaining.decrementAndGet();
            }
        }
    }

    /** Add a unit of work. May be called by workers. */
    public void addWorkUnit(final T workUnit) {
        numWorkUnitsRemaining.incrementAndGet();
        workQueue.add(workUnit);
    }

    /** Add multiple units of work. May be called by workers. */
    public void addWorkUnits(final Collection<T> workUnits) {
        for (final T workUnit : workUnits) {
            addWorkUnit(workUnit);
        }
    }

    /**
     * Stop the workers if any are still running. This should be called after runWorkLoop() exits on the main
     * thread, in case some workers never got started (e.g. when there are fewer than numParallelTasks threads
     * available in the ExecutorService).
     */
    @Override
    public void close() {
        stopAllWorkers.set(true);
        workQueue.clear();
        for (final Future<Void> future : workerFutures) {
            future.cancel(true);
        }
    }

    /** Create a queue. Return a new LinkedBlockingQueue or a PriorityBlockingQueue. */
    public abstract BlockingQueue<T> createQueue();

    /** Process a single work unit. */
    public abstract void processWorkUnit(T workUnit) throws InterruptedException;
}
