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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataCopy;

import java.util.function.Supplier;

public interface MemoryDataFeatures {
	boolean enableAssignmentCopy = false;

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
}
