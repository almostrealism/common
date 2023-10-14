/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Computer;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.mem.Heap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class DefaultComputer implements Computer<MemoryData> {
	private Function<Computation<?>, ComputeContext<MemoryData>> contextFactory;

	public DefaultComputer(Function<Computation<?>, ComputeContext<MemoryData>> contextFactory) {
		this.contextFactory = contextFactory;
	}

	@Override
	public Runnable compileRunnable(Computation<Void> c) {
		return Heap.addCompiled(new AcceleratedComputationOperation<>(contextFactory.apply(c), c, Hardware.enableKernelOps));
	}

	@Override
	public Runnable compileRunnable(Computation<Void> c, boolean kernel) {
		return new AcceleratedComputationOperation<>(contextFactory.apply(c), c, kernel);
	}

	// TODO  The Computation may have a postProcessOutput method that will not be called
	// TODO  when using this method of creating an Evaluable from it. Ideally, that feature
	// TODO  of the Computation would be recognized, and applied after evaluation, so that
	// TODO  the correct type is returned.
	@Override
	public <T extends MemoryData> Evaluable<T> compileProducer(Computation<T> c) {
		return new AcceleratedComputationEvaluable<>(contextFactory.apply(c), c);
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Runnable r) {
		if (r instanceof AcceleratedComputationOperation) {
			return Optional.of(((AcceleratedComputationOperation) r).getComputation());
		} else {
			return Optional.empty();
		}
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Evaluable<T> p) {
		if (p instanceof AcceleratedComputationEvaluable) {
			return Optional.of(((AcceleratedComputationEvaluable) p).getComputation());
		} else {
			return Optional.empty();
		}
	}
}
