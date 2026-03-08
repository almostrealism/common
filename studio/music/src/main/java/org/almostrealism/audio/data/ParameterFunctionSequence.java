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

package org.almostrealism.audio.data;

import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

public class ParameterFunctionSequence implements IntFunction<ParameterFunction> {
	private ParameterFunction[] steps;

	public ParameterFunctionSequence() { }

	public ParameterFunctionSequence(int steps) {
		this.steps = new ParameterFunction[steps];
	}

	public ParameterFunctionSequence(ParameterFunction... steps) {
		this.steps = steps;
	}

	public void setSteps(List<ParameterFunction> steps) { this.steps = steps.toArray(new ParameterFunction[0]); }

	public List<ParameterFunction> getSteps() { return List.of(steps); }

	@Override
	public ParameterFunction apply(int i) {
		return steps[i];
	}

	public static ParameterFunctionSequence random(int steps) {
		return new ParameterFunctionSequence(IntStream.range(0, steps)
				.mapToObj(i -> ParameterFunction.random())
				.toArray(ParameterFunction[]::new));
	}
}
