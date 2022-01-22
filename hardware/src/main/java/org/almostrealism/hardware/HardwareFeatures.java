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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.computations.Loop;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface HardwareFeatures {
	default Runnable compileRunnable(Computation<?> c) {
		return Hardware.getLocalHardware().getComputeContext().getComputer().compileRunnable((Computation<Void>) c);
	}

	default <T extends MemoryData> KernelizedEvaluable<T> compileProducer(Computation<T> c) {
		return (KernelizedEvaluable) Hardware.getLocalHardware().getComputeContext().getComputer().compileProducer(c);
	}

	default <T extends MemoryData> Optional<Computation<T>> decompile(Runnable r) {
		return Hardware.getLocalHardware().getComputeContext().getComputer().decompile(r);
	}

	default <T extends MemoryData> Optional<Computation<T>> decompile(Evaluable<T> r) {
		return Hardware.getLocalHardware().getComputeContext().getComputer().decompile(r);
	}

	default String stringForDouble(double value) {
		return Hardware.getLocalHardware().stringForDouble(value);
	}

	default double doubleForString(String value) {
		return Hardware.getLocalHardware().doubleForString(value);
	}

	default String getNumberTypeName() {
		return Hardware.getLocalHardware().getNumberTypeName();
	}

	default boolean isCastEnabled() {
		return Hardware.getLocalHardware().isGPU() && Hardware.getLocalHardware().isDoublePrecision();
	}

	default <T extends MemoryData> Assignment<T> a(int memLength, Evaluable<T> result, Evaluable<T> value) {
		return a(memLength, () -> result, () -> value);
	}

	default <T extends MemoryData> Assignment<T> a(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		return new Assignment<>(memLength, result, value);
	}

	default Supplier<Runnable> loop(Computation<Void> c, int iterations) {
		if (c instanceof OperationList && !((OperationList) c).isComputation()) {
			Runnable r = ((OperationList) c).get();
			return () -> () -> IntStream.range(0, iterations).forEach(i -> r.run());
		} else {
			return new Loop(c, iterations);
		}
	}

	default Supplier<Runnable> lp(Computation<Void> c, int iterations) { return loop(c, iterations); }
}
