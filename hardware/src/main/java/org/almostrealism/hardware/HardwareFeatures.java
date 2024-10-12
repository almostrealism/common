/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerFeatures;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.computations.DelegatedProducer;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.hardware.instructions.ProcessTreeInstructionsManager;
import org.almostrealism.hardware.mem.MemoryDataCopy;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface HardwareFeatures extends ConsoleFeatures, ProducerFeatures {
	boolean enableAssignmentCopy = false;

	default Runnable compileRunnable(Computation<Void> c) {
		return Hardware.getLocalHardware().getComputer().compileRunnable(c);
	}

	default <T extends MemoryData> KernelizedEvaluable<T> compileProducer(Computation<T> c) {
		return (KernelizedEvaluable) Hardware.getLocalHardware().getComputer().compileProducer(c);
	}

	default <T extends MemoryData> Optional<Computation<T>> decompile(Runnable r) {
		return Hardware.getLocalHardware().getComputer().decompile(r);
	}

	default <T extends MemoryData> Optional<Computation<T>> decompile(Evaluable<T> r) {
		return Hardware.getLocalHardware().getComputer().decompile(r);
	}

	default <T extends MemoryData> Producer<T> instruct(Function<Producer[], Producer<T>> func, Producer... args) {
		Producer delegates[] = Arrays.stream(args)
				.map(arg -> delegate(arg))
				.toArray(Producer[]::new);
		Producer<T> producer = func.apply(delegates);

		if (!(producer instanceof Process)) {
			return producer;
		}

		// TODO  This last step needs to be moved into Hardware, because it needs to retrieve
		// TODO  the ProcessTreeInstructionsManager based on some kind of key that can be reused
		// TODO  later. What we ultimately do here will be different if this is the first time
		// TODO  the key has been seen (and we need to process everything) or if we have seen it
		// TODO  and all we need to do is make sure that every member of the Process tree knows
		// TODO  to try and use the ProcessTreeInstructionsManager to get its instructions instead
		// TODO  of compiling itself from scratch again.
		ProcessTreeInstructionsManager manager = new ProcessTreeInstructionsManager();
		return (Producer) manager.applyAll((Process) producer);
	}

	@Override
	default <T> Producer<?> delegate(Producer<T> producer) {
		return new DelegatedProducer<>(producer);
	}

	default <T extends MemoryData> Assignment<T> a(int memLength,
												   Supplier<Evaluable<? extends T>> result,
												   Supplier<Evaluable<? extends T>> value) {
		return new Assignment<>(memLength, result, value);
	}

	default Supplier<Runnable> copy(Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		return copy(null, source, target, length);
	}

	default Supplier<Runnable> copy(String name, Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		if (enableAssignmentCopy) {
			return new Assignment(length,
					() -> (Evaluable<MemoryData>) args -> target.get(),
					() -> (Evaluable<MemoryData>) args -> source.get());
		} else {
			return new MemoryDataCopy(name, source, target, length);
		}
	}

	default Supplier<Runnable> copy(Producer<? extends MemoryData> source,
									Producer<? extends MemoryData> target, int length) {
		return copy(null, source, target, length);
	}

	default Supplier<Runnable> copy(String name, Producer<? extends MemoryData> source,
									Producer<? extends MemoryData> target, int length) {
		if (enableAssignmentCopy) {
			return new Assignment(length, target, source);
		} else {
			return new MemoryDataCopy(name, source.get()::evaluate, target.get()::evaluate, length);
		}
	}

	default Supplier<Runnable> loop(Supplier<Runnable> c, int iterations) {
		if (!(c instanceof Computation) || (c instanceof OperationList && !((OperationList) c).isComputation())) {
			return () -> {
				Runnable r = c.get();
				return () -> IntStream.range(0, iterations).forEach(i -> r.run());
			};
		} else {
			return new Loop((Computation) c, iterations);
		}
	}

	default Supplier<Runnable> loop(Computation<Void> c, int iterations) {
		if (c instanceof OperationList && !((OperationList) c).isComputation()) {
			return () -> {
				Runnable r = ((OperationList) c).get();
				return () -> IntStream.range(0, iterations).forEach(i -> r.run());
			};
		} else {
			return new Loop(c, iterations);
		}
	}

	default Supplier<Runnable> lp(Computation<Void> c, int iterations) { return loop(c, iterations); }

	static HardwareFeatures getInstance() {
		return new HardwareFeatures() { };
	}
}
