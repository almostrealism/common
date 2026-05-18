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

package org.almostrealism.music.pattern;

import org.almostrealism.music.data.ParameterSet;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A function that maps a {@link ParameterSet} and chord depth to a scale position.
 *
 * <p>Contains one {@link ParameterizedPositionFunction} per chord depth level, each
 * selecting a position within the scale for that chord note. Positions are evaluated
 * independently per depth to allow rich harmonic structure.</p>
 *
 * @see ParameterizedPositionFunction
 * @see PatternElementFactory
 */
public class ChordPositionFunction {
	/** Maximum chord depth supported by this function. */
	public static final int MAX_CHORD_DEPTH = 9;

	/** List of position functions, one per chord depth level. */
	private List<ParameterizedPositionFunction> scalePositions;

	/** Creates an empty {@code ChordPositionFunction} with no scale positions. */
	public ChordPositionFunction() { scalePositions = Collections.emptyList(); }

	/**
	 * Creates a {@code ChordPositionFunction} with the given scale positions.
	 *
	 * @param scalePositions the list of position functions, one per depth
	 */
	private ChordPositionFunction(List<ParameterizedPositionFunction> scalePositions) {
		setScalePositions(scalePositions);
	}

	/** Returns the list of scale position functions, one per chord depth. */
	public List<ParameterizedPositionFunction> getScalePositions() {
		return scalePositions;
	}

	/** Sets the list of scale position functions. */
	public void setScalePositions(List<ParameterizedPositionFunction> scalePositions) {
		this.scalePositions = scalePositions;
	}

	/**
	 * Returns the position for a single chord depth.
	 *
	 * @param params   the parameter set
	 * @param position the base position
	 * @param scale    the time scale
	 * @param depth    the chord depth index
	 * @return the selected position
	 */
	public double apply(ParameterSet params, double position, double scale, int depth) {
		return scalePositions.get(depth).applyPositive(params, position, scale);
	}

	/**
	 * Returns positions for all chord depths from 0 to {@code depth} (exclusive).
	 *
	 * @param params   the parameter set
	 * @param position the base position
	 * @param scale    the time scale
	 * @param depth    the number of chord positions to return
	 * @return a list of selected positions
	 */
	public List<Double> applyAll(ParameterSet params, double position, double scale, int depth) {
		return IntStream.range(0, depth)
				.mapToObj(i -> apply(params, position, scale, i))
				.collect(Collectors.toList());
	}

	/**
	 * Creates a randomly initialized {@code ChordPositionFunction}.
	 *
	 * @return a new instance with random position functions for all chord depths
	 */
	public static ChordPositionFunction random() {
		return new ChordPositionFunction(IntStream.range(0, MAX_CHORD_DEPTH)
				.mapToObj(i -> ParameterizedPositionFunction.random())
				.collect(Collectors.toList()));
	}
}
