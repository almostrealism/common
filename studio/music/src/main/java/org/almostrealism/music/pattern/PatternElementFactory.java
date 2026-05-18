/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Factory for creating {@link PatternElement}s with parameterized selection functions.
 *
 * <p>{@code PatternElementFactory} is responsible for generating musical events within
 * pattern layers. It uses a collection of parameterized functions to determine:</p>
 * <ul>
 *   <li>Note selection (which sample to use)</li>
 *   <li>Note duration</li>
 *   <li>Chord voicing positions</li>
 *   <li>Repeat patterns</li>
 *   <li>Volume and filter envelopes</li>
 * </ul>
 *
 * <h2>Genetic Algorithm Integration</h2>
 *
 * <p>All selection functions are parameterized, meaning their behavior is controlled
 * by {@link ParameterSet} values. This enables genetic algorithm optimization where
 * the parameters evolve to produce musically interesting patterns.</p>
 *
 * <h2>Key Configuration</h2>
 *
 * <ul>
 *   <li>{@code MAX_LAYERS}: Maximum layer depth for note selection (default: 10)</li>
 *   <li>{@code noteLengthFactor}: Maximum note duration relative to scale (default: 0.75)</li>
 *   <li>{@code enableVolumeEnvelope}: Apply volume shaping to notes (default: true)</li>
 *   <li>{@code enableFilterEnvelope}: Apply filter modulation to notes (default: true)</li>
 * </ul>
 *
 * <h2>Element Creation</h2>
 *
 * <p>The {@link #apply} method creates a pattern element by:</p>
 * <ol>
 *   <li>Computing position based on parity (LEFT/NONE/RIGHT)</li>
 *   <li>Selecting note audio via the note factory</li>
 *   <li>Applying volume and filter envelopes if enabled</li>
 *   <li>Determining chord positions and scale traversal</li>
 *   <li>Computing repeat count and duration</li>
 * </ol>
 *
 * @see PatternElement
 * @see PatternNoteFactory
 * @see ParameterizedPositionFunction
 * @see PatternLayerManager
 *
 * @author Michael Murray
 */
public class PatternElementFactory implements ConsoleFeatures {
	/** Whether volume envelope processing is enabled for generated elements. */
	public static boolean enableVolumeEnvelope = true;

	/** Whether filter envelope processing is enabled for generated elements. */
	public static boolean enableFilterEnvelope = true;

	/** Maximum number of note layers per pattern element. */
	public static int MAX_LAYERS = 10;

	/** The duration strategy applied when generating chord elements. */
	public static NoteDurationStrategy CHORD_STRATEGY = NoteDurationStrategy.FIXED;

	/**
	 * The maximum duration of a note, relative to the scale of the element
	 * being generated. A value of 1.0 would mean that the note may be only
	 * as long as the scale (eg, a 1/4 note would be at most 1/4th the length
	 * of a measure, a 1/8 note would be at most 1/8th the length of a measure,
	 * etc).
	 */
	public static double noteLengthFactor = 0.75;

	/** Optional distribution array for tracking repeat selections; may be null. */
	public static int[] REPEAT_DIST;

	/** Factory for creating pattern notes. */
	private PatternNoteFactory noteFactory;

	/** Function selecting which note to play for the element. */
	private ParameterizedPositionFunction noteSelection;

	/** Per-layer note selection functions (one per layer up to {@link #MAX_LAYERS}). */
	private List<ParameterizedPositionFunction> noteLayerSelections;

	/** Function selecting the note duration. */
	private ParameterFunction noteLengthSelection;

	/** Volume envelope applied to generated notes. */
	private ParameterizedVolumeEnvelope volumeEnvelope;

	/** Filter envelope applied to generated notes. */
	private ParameterizedFilterEnvelope filterEnvelope;

	/** Function selecting which chord note to use. */
	private ChordPositionFunction chordNoteSelection;

	/** Function selecting the repeat position. */
	private ParameterizedPositionFunction repeatSelection;

	/** Creates a {@code PatternElementFactory} with a default note factory and random selection functions. */
	public PatternElementFactory() {
		setNoteFactory(new PatternNoteFactory());
		initSelectionFunctions();
	}

	/** Initializes all selection functions with random values. */
	public void initSelectionFunctions() {
		noteSelection = ParameterizedPositionFunction.random();
		noteLayerSelections = IntStream.range(0, MAX_LAYERS)
				.mapToObj(i -> ParameterizedPositionFunction.random())
				.collect(Collectors.toList());
		noteLengthSelection = ParameterFunction.random();
		volumeEnvelope = ParameterizedVolumeEnvelope.random(ParameterizedVolumeEnvelope.Mode.STANDARD_NOTE);
		filterEnvelope = ParameterizedFilterEnvelope.random(ParameterizedFilterEnvelope.Mode.STANDARD_NOTE);
		chordNoteSelection = ChordPositionFunction.random();
		repeatSelection = ParameterizedPositionFunction.random();
	}

	/** Returns the note factory used to create pattern notes. */
	public PatternNoteFactory getNoteFactory() {
		return noteFactory;
	}

	/** Sets the note factory. */
	public void setNoteFactory(PatternNoteFactory noteFactory) {
		this.noteFactory = noteFactory;
	}

	/** Returns the note selection function. */
	public ParameterizedPositionFunction getNoteSelection() {
		return noteSelection;
	}

	/** Sets the note selection function. */
	public void setNoteSelection(ParameterizedPositionFunction noteSelection) {
		this.noteSelection = noteSelection;
	}

	/** Returns the per-layer note selection functions. */
	public List<ParameterizedPositionFunction> getNoteLayerSelections() {
		return noteLayerSelections;
	}

	/** Sets the per-layer note selection functions. */
	public void setNoteLayerSelections(List<ParameterizedPositionFunction> noteLayerSelections) {
		this.noteLayerSelections = noteLayerSelections;
	}

	/** Returns the note length selection function. */
	public ParameterFunction getNoteLengthSelection() { return noteLengthSelection; }

	/** Sets the note length selection function. */
	public void setNoteLengthSelection(ParameterFunction noteLengthSelection) { this.noteLengthSelection = noteLengthSelection; }

	/** Returns the volume envelope. */
	public ParameterizedVolumeEnvelope getVolumeEnvelope() { return volumeEnvelope; }

	/** Sets the volume envelope. */
	public void setVolumeEnvelope(ParameterizedVolumeEnvelope volumeEnvelope) { this.volumeEnvelope = volumeEnvelope; }

	/** Returns the filter envelope. */
	public ParameterizedFilterEnvelope getFilterEnvelope() { return filterEnvelope; }

	/** Sets the filter envelope. */
	public void setFilterEnvelope(ParameterizedFilterEnvelope filterEnvelope) { this.filterEnvelope = filterEnvelope; }

	/** Returns the chord note selection function. */
	public ChordPositionFunction getChordNoteSelection() {
		return chordNoteSelection;
	}

	/** Sets the chord note selection function. */
	public void setChordNoteSelection(ChordPositionFunction chordNoteSelection) {
		this.chordNoteSelection = chordNoteSelection;
	}

	/** Returns the repeat selection function. */
	public ParameterizedPositionFunction getRepeatSelection() {
		return repeatSelection;
	}

	/** Sets the repeat selection function. */
	public void setRepeatSelection(ParameterizedPositionFunction repeatSelection) {
		this.repeatSelection = repeatSelection;
	}

	/**
	 * Creates a {@link PatternElement} at the given position and parity.
	 *
	 * <p>TODO: This should take explicit instruction for whether to apply note duration;
	 * relying only on {@code isMelodic} limits its use.</p>
	 *
	 * @param parity                 the position parity (LEFT, RIGHT, or NONE)
	 * @param position               the base position in measures
	 * @param scale                  the time scale for the element
	 * @param bias                   the selection bias
	 * @param scaleTraversalStrategy the traversal strategy
	 * @param depth                  the chord/scale depth
	 * @param repeat                 whether to apply repeat logic
	 * @param melodic                whether this element is melodic
	 * @param params                 the parameter set
	 * @return an {@code Optional} containing the element, or empty if generation fails
	 */
	public Optional<PatternElement> apply(ElementParity parity, double position,
										  double scale, double bias,
										  ScaleTraversalStrategy scaleTraversalStrategy,
										  int depth, boolean repeat, boolean melodic,
										  ParameterSet params) {
		double pos;

		if (parity == ElementParity.LEFT) {
			pos = position - scale;
		} else if (parity == ElementParity.RIGHT) {
			pos = position + scale;
		} else {
			pos = position;
		}

		double note = noteSelection.apply(params, pos, scale) + bias;
		while (note > 1) note -= 1;
		if (note < 0.0) return Optional.empty();

		double[] noteLayers =
				IntStream.range(0, getNoteFactory().getLayerCount())
						.mapToDouble(i -> noteLayerSelections.get(i).apply(params, pos, scale))
						.map(i -> i + bias)
						.map(i -> {
							while (i > 1) i -= 1;
							while (i < 0) i += 1;
							return i;
						})
						.toArray();

		PatternNote choice = getNoteFactory().apply(params, null, melodic, noteLayers);

		PatternNote main = choice;
		if (enableFilterEnvelope && melodic) main = filterEnvelope.apply(params, ChannelInfo.Voicing.MAIN, main);
		if (enableVolumeEnvelope && melodic) main = volumeEnvelope.apply(params, ChannelInfo.Voicing.MAIN, main);

		PatternNote wet = choice;
		if (enableFilterEnvelope && melodic) wet = filterEnvelope.apply(params, ChannelInfo.Voicing.WET, wet);
		if (enableVolumeEnvelope) wet = volumeEnvelope.apply(params, ChannelInfo.Voicing.WET, wet);

		PatternElement element = new PatternElement(main, wet, pos);
		element.setScalePosition(chordNoteSelection.applyAll(params, pos, scale, depth));
		element.setDurationStrategy(melodic ?
				(scaleTraversalStrategy == ScaleTraversalStrategy.CHORD ?
						CHORD_STRATEGY : NoteDurationStrategy.FIXED) :
					NoteDurationStrategy.NONE);


		double ls = Math.min(scale, 1.0);
		element.setNoteDurationSelection(ls * noteLengthFactor * noteLengthSelection.positive().apply(params));

		double r = repeatSelection.apply(params, pos, scale);

		if (!repeat || r <= 0) {
			element.setRepeatCount(1);
		} else {
			int c;
			for (c = 0; r < 3 & c < 6; c++) {
				r *= 1.8;
			}

			element.setRepeatCount(c);
		}

		if (REPEAT_DIST != null) {
			if (repeat) {
				REPEAT_DIST[element.getRepeatCount()]++;
			} else {
				REPEAT_DIST[0]++;
			}
		}

		element.setScaleTraversalStrategy(scaleTraversalStrategy);

		if (noteLengthFactor <= 0.5) {
			// If the note length factor is significantly less than 1.0,
			// there is ample time to repeat the note sooner
			element.setRepeatDuration(ls * 0.5);
		} else {
			element.setRepeatDuration(ls);
		}

		return Optional.of(element);
	}

	@Override
	public Console console() {
		return CellFeatures.console;
	}
}
