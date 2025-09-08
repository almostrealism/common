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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.profile.CompilationTimingListener;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public abstract class AbstractComputeContext<T extends DataContext<MemoryData>> implements ComputeContext<MemoryData> {
	public static CompilationTimingListener compilationTimingListener;
	public static int threadId = 0;

	private final T dc;
	private final Executor executor;
	private final ThreadGroup executorGroup;

	protected AbstractComputeContext(T dc) {
		this.dc = dc;
		this.executorGroup = new ThreadGroup("ComputeContext");
		this.executor = Executors.newFixedThreadPool(KernelPreferences.getEvaluationParallelism(),
				r -> new Thread(executorGroup, r, "ComputeContext-" + (threadId++)));
	}

	@Override
	public void runLater(Runnable runnable) {
		executor.execute(runnable);
	}

	@Override
	public boolean isExecutorThread() {
		return Thread.currentThread().getThreadGroup() == executorGroup;
	}

	public T getDataContext() { return dc; }

	protected void recordCompilation(Scope<?> scope, Supplier<String> source, long nanos) {
		if (compilationTimingListener != null) {
			compilationTimingListener.recordCompilation(scope, source.get(), nanos);
		}
	}
}
