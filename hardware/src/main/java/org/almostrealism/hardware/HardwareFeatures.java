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
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerFeatures;
import org.almostrealism.hardware.computations.DelegatedProducer;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface HardwareFeatures extends ProducerFeatures, MemoryDataFeatures, ConsoleFeatures {

	default <T extends MemoryData> Producer<T> instruct(String key,
														Function<Producer[], Producer<T>> func,
														Producer... args) {
		Producer delegates[] = Arrays.stream(args)
				.map(arg -> delegate(arg))
				.toArray(Producer[]::new);
		return (Producer) Hardware.getLocalHardware().getComputer()
					.createContainer(key, func, this::substitute, this::delegate, delegates);
	}

	@Override
	default <T> Producer<?> delegate(Producer<T> original, Producer<T> actual) {
		return new DelegatedProducer<>(actual);
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
