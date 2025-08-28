/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.concurrent;

import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

public class SuspendableThreadPoolExecutor extends ThreadPoolExecutor implements ConsoleFeatures {
	private volatile double minPriorityThreshold;
	private final Object suspensionLock = new Object();
	private ToDoubleFunction<Runnable> priority;

	public SuspendableThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
										 long keepAliveTime, TimeUnit unit,
										 PriorityBlockingQueue<? extends Runnable> queue) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit,
				(PriorityBlockingQueue) queue);
		minPriorityThreshold = -1.0;
	}

	public ToDoubleFunction<Runnable> getPriority() {
		return priority;
	}

	public void setPriority(ToDoubleFunction<Runnable> priority) {
		this.priority = priority;
	}

	public void suspendTasks(double minPriority) {
		setPriorityThreshold(minPriority);
	}

	public void resumeAllTasks() {
		setPriorityThreshold(-1.0);
	}

	public void setPriorityThreshold(double minPriority) {
		if (minPriority == minPriorityThreshold) {
			return;
		}

		synchronized (suspensionLock) {
			this.minPriorityThreshold = minPriority;
			suspensionLock.notifyAll();
		}
	}

	@Override
	protected void beforeExecute(Thread t, Runnable r) {
		super.beforeExecute(t, r);
		if (priority == null) return;

		log("Priority for " + r + " is " + priority.applyAsDouble(r) +
				" (threshold is " + minPriorityThreshold + ")");

		synchronized (suspensionLock) {
			while (priority.applyAsDouble(r) < minPriorityThreshold) {
				try {
					// Wait until resumed or the threshold is lowered
					suspensionLock.wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}
}
