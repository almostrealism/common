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

package org.almostrealism.audio.data;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MultipleParameterFunction {
	private List<ParameterFunction> functions;

	public MultipleParameterFunction() {
	}

	protected MultipleParameterFunction(List<ParameterFunction> functions) {
		this.functions = functions;
	}

	public List<ParameterFunction> getFunctions() {
		return functions;
	}

	public void setFunctions(List<ParameterFunction> functions) {
		this.functions = functions;
	}

	public List<Double> apply(ParameterSet params) {
		return functions.stream()
						.map(f -> f.positive().apply(params))
						.collect(Collectors.toList());
	}

	public static MultipleParameterFunction random(int count) {
		return new MultipleParameterFunction(
				IntStream.range(0, count)
							.mapToObj(i -> ParameterFunction.random())
						.collect(Collectors.toList()));
	}
}
