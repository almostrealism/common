/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.relation;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;

import java.util.stream.DoubleStream;
import java.util.stream.Stream;

public class ParallelismSettings {
	public static double parallelismValue(long count) {
		return 1 + (4096 * Math.log(count) / Math.log(2));
	}

	public static double memoryCost(long size) {
		return Math.pow(size, 1.5) / 4096;
	}

	public static double score(long parallelism, long size) {
		return parallelismValue(parallelism) - memoryCost(size);
	}

	public static <T> DoubleStream scores(Stream<T> processes) {
		return processes.mapToDouble(c -> score(ParallelProcess.parallelism(c), Process.outputSize(c)));
	}
}
