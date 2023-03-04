/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.Producer;

import java.util.stream.IntStream;

public class Input {
	private Input() { }

	public static <T> Producer<T> value(Class<T> type, int argIndex) {
		return PassThroughEvaluable.of(type, argIndex);
	}

	public static <T> Producer<T> value(Class<T> type, int argIndex, int kernelDimension) {
		return PassThroughEvaluable.of(type, argIndex, kernelDimension);
	}

	public static <T> Producer<T> value(int memLength, int argIndex) {
		return new PassThroughProducer(memLength, argIndex);
	}

	public static <T> Producer<T> value(int memLength, int argIndex, int kernelDimension) {
		return new PassThroughProducer(memLength, argIndex, kernelDimension);
	}

	public static Producer[] generateArguments(int memLength, int first, int count) {
		return IntStream.range(0, count).mapToObj(i -> value(memLength, first + i)).toArray(Producer[]::new);
	}
}
