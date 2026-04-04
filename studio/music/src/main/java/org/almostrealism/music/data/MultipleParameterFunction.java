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

package org.almostrealism.music.data;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A collection of {@link ParameterFunction}s that can be applied together to produce
 * multiple output values from a single {@link ParameterSet}.
 *
 * <p>This is used to generate volume levels for multiple audio layers simultaneously,
 * where each function in the list controls the volume of one layer.</p>
 *
 * @see ParameterFunction
 * @see ParameterSet
 */
public class MultipleParameterFunction {
	/** The list of parameter functions to apply. */
	private List<ParameterFunction> functions;

	/** Creates an empty {@code MultipleParameterFunction}. */
	public MultipleParameterFunction() {
	}

	/**
	 * Creates a {@code MultipleParameterFunction} with the given functions.
	 *
	 * @param functions the list of parameter functions
	 */
	protected MultipleParameterFunction(List<ParameterFunction> functions) {
		this.functions = functions;
	}

	/** Returns the list of parameter functions. */
	public List<ParameterFunction> getFunctions() {
		return functions;
	}

	/** Sets the list of parameter functions. */
	public void setFunctions(List<ParameterFunction> functions) {
		this.functions = functions;
	}

	/**
	 * Applies all functions to the given parameter set and returns the results.
	 *
	 * @param params the parameter set to evaluate
	 * @return a list of output values, one per function
	 */
	public List<Double> apply(ParameterSet params) {
		return functions.stream()
						.map(f -> f.positive().apply(params))
						.collect(Collectors.toList());
	}

	/**
	 * Creates a {@code MultipleParameterFunction} with the given number of random functions.
	 *
	 * @param count the number of functions to generate
	 * @return a new instance with randomly initialized functions
	 */
	public static MultipleParameterFunction random(int count) {
		return new MultipleParameterFunction(
				IntStream.range(0, count)
							.mapToObj(i -> ParameterFunction.random())
						.collect(Collectors.toList()));
	}
}
