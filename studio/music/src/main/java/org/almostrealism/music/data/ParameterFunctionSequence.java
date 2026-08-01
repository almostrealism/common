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

package org.almostrealism.music.data;

import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * An indexed sequence of {@link ParameterFunction}s, one per step.
 *
 * <p>Used by the {@link org.almostrealism.music.sequence.GridSequencer} to select
 * audio samples for each grid step based on the current {@link ParameterSet}.</p>
 *
 * @see ParameterFunction
 * @see ParameterSet
 */
public class ParameterFunctionSequence implements IntFunction<ParameterFunction> {
	/** The array of functions, one per step. */
	private ParameterFunction[] steps;

	/** Creates an empty {@code ParameterFunctionSequence}. */
	public ParameterFunctionSequence() { }

	/**
	 * Creates a sequence with the given number of null steps.
	 *
	 * @param steps the number of steps to allocate
	 */
	public ParameterFunctionSequence(int steps) {
		this.steps = new ParameterFunction[steps];
	}

	/**
	 * Creates a sequence using the given functions.
	 *
	 * @param steps the functions for each step
	 */
	public ParameterFunctionSequence(ParameterFunction... steps) {
		this.steps = steps;
	}

	/** Sets the steps from a list of parameter functions. */
	public void setSteps(List<ParameterFunction> steps) { this.steps = steps.toArray(new ParameterFunction[0]); }

	/** Returns the steps as an immutable list. */
	public List<ParameterFunction> getSteps() { return List.of(steps); }

	/**
	 * Returns the {@link ParameterFunction} for the given step index.
	 *
	 * @param i the step index
	 * @return the function at that step
	 */
	@Override
	public ParameterFunction apply(int i) {
		return steps[i];
	}

	/**
	 * Creates a sequence of the given length with randomly initialized functions.
	 *
	 * @param steps the number of steps
	 * @return a new randomly initialized sequence
	 */
	public static ParameterFunctionSequence random(int steps) {
		return new ParameterFunctionSequence(IntStream.range(0, steps)
				.mapToObj(i -> ParameterFunction.random())
				.toArray(ParameterFunction[]::new));
	}
}
