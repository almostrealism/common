/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.concurrent;

import org.almostrealism.io.ConsoleFeatures;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Priority-based thread pool executor that can suspend low-priority tasks when high-priority
 * work is submitted.
 *
 * <p>Tasks are queued in a {@link PriorityBlockingQueue} ordered by a configurable priority
 * function. When a minimum priority threshold is active, worker threads will yield their current
 * low-priority task back to the queue and wait until either the threshold changes or a
 * sufficiently high-priority task arrives.</p>
 *
 * @see TaskAbandonedException
 */
public class SuspendableThreadPoolExecutor extends ThreadPoolExecutor implements ConsoleFeatures {
	/** Monotonically increasing counter for generating unique thread group names. */
	private static long id = 0;

	/** Monitor used for priority-based suspension and resumption of worker threads. */
	private final Object suspensionLock = new Object();

	/** Minimum priority required for a task to begin execution; -1.0 means no restriction. */
    private volatile double minPriorityThreshold;
	/** Flag set when a high-priority task is submitted, used to wake suspended low-priority workers. */
	private volatile boolean highPriorityTaskAdded;

	/** Function mapping a {@link Runnable} to its scheduling priority (higher is more urgent). */
    private ToDoubleFunction<Runnable> priority;

	/**
	 * Creates a single-threaded executor with a default uniform priority of 0.5 for all tasks.
	 */
	public SuspendableThreadPoolExecutor() {
		this(r -> 0.5);
	}

	/**
	 * Creates a single-threaded executor using the given priority function.
	 *
	 * @param priority Function that computes the priority of a runnable (higher = more urgent)
	 */
	public SuspendableThreadPoolExecutor(ToDoubleFunction<Runnable> priority) {
		this(1, 1, 60L, TimeUnit.SECONDS,
				new PriorityBlockingQueue<>(100,
						Comparator.comparingDouble(priority).reversed()));
		setPriority(priority);
	}

	/**
	 * Creates a configurable executor backed by the given priority queue.
	 *
	 * @param corePoolSize Minimum number of worker threads to keep alive
	 * @param maximumPoolSize Maximum number of worker threads
	 * @param keepAliveTime Idle thread keep-alive time
	 * @param unit Time unit for {@code keepAliveTime}
	 * @param queue Priority-ordered work queue
	 */
    public SuspendableThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                                         long keepAliveTime, TimeUnit unit,
                                         PriorityBlockingQueue<? extends Runnable> queue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit,
                (PriorityBlockingQueue) queue,
				SuspendableThreadPoolExecutor.threadFactory(id++));
        minPriorityThreshold = -1.0;
    }

	/**
	 * Returns the current priority function.
	 *
	 * @return Priority function mapping runnables to numeric priority values
	 */
    public ToDoubleFunction<Runnable> getPriority() {
        return priority;
    }

	/**
	 * Sets the priority function used to order tasks and enforce suspension thresholds.
	 *
	 * @param priority New priority function; higher values indicate more urgent tasks
	 */
    public void setPriority(ToDoubleFunction<Runnable> priority) {
        this.priority = priority;
    }

	/**
	 * Suspends execution of tasks below the given minimum priority.
	 *
	 * <p>Tasks already executing are not interrupted, but new tasks below the threshold will
	 * block until the threshold is lowered or a high-priority task arrives.</p>
	 *
	 * @param minPriority Minimum priority threshold; tasks below this value will wait
	 */
    public void suspendTasks(double minPriority) {
        setPriorityThreshold(minPriority);
    }

	/**
	 * Resumes execution of all suspended tasks by removing the priority threshold.
	 */
    public void resumeAllTasks() {
        setPriorityThreshold(-1.0);
    }

	/**
	 * Sets the minimum priority threshold, waking any suspended threads to re-evaluate.
	 *
	 * @param minPriority New threshold; -1.0 disables suspension
	 */
    public void setPriorityThreshold(double minPriority) {
        if (minPriority == minPriorityThreshold) {
            return;
        }

        synchronized (suspensionLock) {
            this.minPriorityThreshold = minPriority;
            suspensionLock.notifyAll();
        }
    }

	/**
	 * Returns the current minimum priority threshold.
	 *
	 * @return Current threshold; -1.0 means no suspension is active
	 */
    public double getPriorityThreshold() {
        return minPriorityThreshold;
    }

    @Override
    public void execute(Runnable command) {
		super.execute(command);

        // If this task exceeds the priority threshold, make sure
		// to notify suspended threads to take it up instead
        if (priority != null && priority.applyAsDouble(command) >= minPriorityThreshold) {
            synchronized (suspensionLock) {
                highPriorityTaskAdded = true;
                suspensionLock.notifyAll();
            }
        }
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        if (priority == null) return;

        double taskPriority = priority.applyAsDouble(r);
        
        synchronized (suspensionLock) {
            while (taskPriority < minPriorityThreshold) {
                // Check if a high-priority task was added while we were waiting
                if (highPriorityTaskAdded) {
					highPriorityTaskAdded = false;

					// Put the current low-priority task back in the queue
                    log("Abandoning " + r);
                    getQueue().offer(r);

                    throw new TaskAbandonedException("Task abandoned for higher priority work");
                }

                if (taskPriority < minPriorityThreshold) {
                    log("Priority for " + r + " is " + taskPriority +
                            " (threshold is " + minPriorityThreshold + ")");
                }

                try {
                    // Wait until threshold is altered
					// or new priority task arrives
                    suspensionLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
                // Check for updated priority in case it has changed
                taskPriority = priority.applyAsDouble(r);
            }
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        if (t instanceof TaskAbandonedException) {
            log("Skipped " + r + " due to higher priority tasks");
            return;
        }

        super.afterExecute(r, t);
    }

	/**
	 * Creates a thread factory for a new suspension-aware thread pool with the given pool ID.
	 *
	 * <p>The factory's thread group suppresses stack traces for {@link TaskAbandonedException}.</p>
	 *
	 * @param id Pool identifier used in thread group and thread names
	 * @return Thread factory producing named threads in the pool's thread group
	 */
	public static ThreadFactory threadFactory(long id) {
		return threadFactory(new ThreadGroup("SuspendableThreadPool-" + id) {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				if (e instanceof TaskAbandonedException) {
					// Don't print stack traces for task abandonment
					return;
				}

				super.uncaughtException(t, e);
			}
		});
	}

	/**
	 * Creates a thread factory that produces named threads in the given thread group.
	 *
	 * @param group Thread group for all created threads
	 * @return Thread factory with sequential thread naming
	 */
	public static ThreadFactory threadFactory(ThreadGroup group) {
		return new ThreadFactory() {
			int id = 0;

			@Override
			public Thread newThread(Runnable r) {
				return new Thread(group, r, group.getName() + "-" + id++);
			}
		};
	}

	/**
	 * Exception thrown when a worker abandons its current task to allow higher-priority work to proceed.
	 *
	 * <p>The abandoned task is re-queued before this exception is thrown, so no work is lost.</p>
	 */
	protected static class TaskAbandonedException extends RuntimeException {
		/**
		 * Creates a task abandonment exception with a descriptive message.
		 *
		 * @param message Description of why the task was abandoned
		 */
		public TaskAbandonedException(String message) { super(message); }
	}
}