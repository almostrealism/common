/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.pattern;

import org.almostrealism.audio.data.ParameterSet;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ChordPositionFunction {
	public static final int MAX_CHORD_DEPTH = 9;

	private List<ParameterizedPositionFunction> scalePositions;

	public ChordPositionFunction() { scalePositions = Collections.emptyList(); }

	private ChordPositionFunction(List<ParameterizedPositionFunction> scalePositions) {
		setScalePositions(scalePositions);
	}

	public List<ParameterizedPositionFunction> getScalePositions() {
		return scalePositions;
	}

	public void setScalePositions(List<ParameterizedPositionFunction> scalePositions) {
		this.scalePositions = scalePositions;
	}

	public double apply(ParameterSet params, double position, double scale, int depth) {
		return scalePositions.get(depth).applyPositive(params, position, scale);
	}

	public List<Double> applyAll(ParameterSet params, double position, double scale, int depth) {
		return IntStream.range(0, depth)
				.mapToObj(i -> apply(params, position, scale, i))
				.collect(Collectors.toList());
	}

	public static ChordPositionFunction random() {
		return new ChordPositionFunction(IntStream.range(0, MAX_CHORD_DEPTH)
				.mapToObj(i -> ParameterizedPositionFunction.random())
				.collect(Collectors.toList()));
	}
}
