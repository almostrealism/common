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

import java.util.Optional;
import java.util.function.Supplier;

public interface HardwareFeatures {
	default Runnable compileRunnable(Computation<?> c) {
		return Hardware.getLocalHardware().getComputer().compileRunnable(c);
	}

	default <T extends MemWrapper> KernelizedEvaluable<T> compileProducer(Computation<T> c) {
		return (KernelizedEvaluable) Hardware.getLocalHardware().getComputer().compileProducer(c);
	}

	default <T extends MemWrapper> Optional<Computation<T>> decompile(Runnable r) {
		return Hardware.getLocalHardware().getComputer().decompile(r);
	}

	default <T extends MemWrapper> Optional<Computation<T>> decompile(Evaluable<T> r) {
		return Hardware.getLocalHardware().getComputer().decompile(r);
	}

	default String stringForDouble(double value) {
		return Hardware.getLocalHardware().stringForDouble(value);
	}

	default double doubleForString(String value) {
		return Hardware.getLocalHardware().doubleForString(value);
	}

	default String getNumberType() {
		return Hardware.getLocalHardware().getNumberType();
	}

	default boolean isCastEnabled() {
		return Hardware.getLocalHardware().isGPU() && Hardware.getLocalHardware().isDoublePrecision();
	}

	default <T extends MemWrapper> Supplier<Runnable> a(int memLength, Evaluable<T> result, Evaluable<T> value) {
		return a(memLength, () -> result, () -> value);
	}

	default <T extends MemWrapper> Supplier<Runnable> a(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		return new Assignment<>(memLength, result, value);
	}
}
