/*
 * Copyright 2026 Michael Murray
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

import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.music.notes.PatternNoteLayer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tests for {@link PatternElementFactory}, {@link PatternLayerSeeds} and
 * {@link PatternNoteFactory}. Every selection function of the factory is replaced
 * with a deterministic one (see
 * {@link ParameterizedPositionFunctionTest#constantAtWholeMeasures(double)}) so the
 * placement, voicing, duration and repetition decisions can be asserted exactly.
 * All positions used are whole measures.
 */
public class PatternElementFactoryTest extends TestSuiteBase {

	/** Parameters passed to the factory; the deterministic functions ignore them. */
	private static final ParameterSet PARAMS = new ParameterSet(0.1, 0.2, 0.3);

	/** Tolerance for values derived from trigonometric functions. */
	private static final double EPSILON = 1e-9;

	/**
	 * Creates a factory whose note selection evaluates to {@code note} and whose
	 * repeat selection evaluates to {@code repeat} at every whole-measure position.
	 *
	 * @param note   the raw note selection value
	 * @param repeat the raw repeat selection value
	 * @return the deterministic factory
	 */
	private static PatternElementFactory factory(double note, double repeat) {
		PatternElementFactory factory = new PatternElementFactory();
		factory.setNoteSelection(ParameterizedPositionFunctionTest.constantAtWholeMeasures(note));
		factory.setNoteLayerSelections(List.of(
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.2),
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.4),
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(-0.3)));
		factory.setNoteLengthSelection(ParameterizedPositionFunctionTest.constantFunction(1.0));
		factory.setRepeatSelection(ParameterizedPositionFunctionTest.constantAtWholeMeasures(repeat));

		ChordPositionFunction chord = new ChordPositionFunction();
		chord.setScalePositions(List.of(
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.25),
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(-0.75),
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.5)));
		factory.setChordNoteSelection(chord);
		return factory;
	}

	/**
	 * Applies the factory at measure 2 with scale 1 and bias 0.5.
	 *
	 * @param factory  the factory to apply
	 * @param parity   the parity to use
	 * @param repeat   whether repetition is allowed
	 * @param melodic  whether the element is melodic
	 * @param strategy the scale traversal strategy
	 * @return the created element
	 */
	private static PatternElement create(PatternElementFactory factory, ElementParity parity,
										 boolean repeat, boolean melodic,
										 ScaleTraversalStrategy strategy) {
		Optional<PatternElement> element = factory.apply(parity, 2.0, 1.0, 0.5,
				strategy, 2, repeat, melodic, PARAMS);
		Assert.assertTrue("note selection 0.3 + bias 0.5 must produce an element",
				element.isPresent());
		return element.get();
	}

	/**
	 * Returns the selection values of the {@link PatternNoteAudioChoice} layers of a note.
	 *
	 * @param note the note to inspect
	 * @return the layer selections in order
	 */
	private static List<Double> selections(PatternNote note) {
		return note.getLayers().stream()
				.map(l -> ((PatternNoteAudioChoice) l).getNoteAudioSelection())
				.collect(Collectors.toList());
	}

	/** Parity places the element before, at, or after the base position by one scale. */
	@Test(timeout = 30000)
	public void parityOffsetsPosition() {
		PatternElementFactory factory = factory(0.3, 1.0);
		ScaleTraversalStrategy chord = ScaleTraversalStrategy.CHORD;

		Assert.assertEquals(1.0, create(factory, ElementParity.LEFT, false, false, chord).getPosition(), 0.0);
		Assert.assertEquals(2.0, create(factory, ElementParity.NONE, false, false, chord).getPosition(), 0.0);
		Assert.assertEquals(2.0, create(factory, null, false, false, chord).getPosition(), 0.0);
		Assert.assertEquals(3.0, create(factory, ElementParity.RIGHT, false, false, chord).getPosition(), 0.0);
	}

	/** A note selection pushed below zero by the bias produces no element; values above one wrap. */
	@Test(timeout = 30000)
	public void biasControlsElementPresence() {
		Assert.assertFalse(factory(0.3, 1.0).apply(ElementParity.NONE, 2.0, 1.0, -0.5,
				ScaleTraversalStrategy.CHORD, 1, false, false, PARAMS).isPresent());

		Assert.assertTrue("0.9 + 0.5 wraps to 0.4 and is kept",
				factory(0.9, 1.0).apply(ElementParity.NONE, 2.0, 1.0, 0.5,
						ScaleTraversalStrategy.CHORD, 1, false, false, PARAMS).isPresent());
	}

	/**
	 * A percussive element uses the raw layered note for its main voicing and a
	 * volume-enveloped copy for its wet voicing, with layer selections biased and
	 * wrapped into {@code [0, 1]}. Both voicings have the shape the batched
	 * percussion renderer accepts.
	 */
	@Test(timeout = 30000)
	public void percussiveElementVoicing() {
		PatternElement element = create(factory(0.3, 1.0), ElementParity.NONE, false, false,
				ScaleTraversalStrategy.CHORD);

		PatternNote main = element.getNote(ChannelInfo.Voicing.MAIN);
		PatternNote wet = element.getNote(ChannelInfo.Voicing.WET);

		List<Double> layers = selections(main);
		Assert.assertEquals(3, layers.size());
		Assert.assertEquals(0.7, layers.get(0), EPSILON);
		Assert.assertEquals(0.9, layers.get(1), EPSILON);
		Assert.assertEquals("-0.3 + 0.5 = 0.2", 0.2, layers.get(2), EPSILON);

		Assert.assertNull("the dry voicing has no envelope", main.getFilter());
		Assert.assertTrue(wet.getFilter() instanceof ParameterizedVolumeEnvelope.Filter);
		Assert.assertSame(main, wet.getDelegate());

		Assert.assertTrue(BatchedNoteInputs.isPercussionSssShape(main));
		Assert.assertTrue(BatchedNoteInputs.isPercussionSssShape(wet));
		Assert.assertFalse(BatchedNoteInputs.isMelodicSssShape(main));

		Assert.assertEquals(NoteDurationStrategy.NONE, element.getDurationStrategy());
	}

	/**
	 * A melodic element wraps blended layers in filter and volume envelopes on both
	 * voicings, producing the shape the batched melodic renderer accepts.
	 */
	@Test(timeout = 30000)
	public void melodicElementVoicing() {
		PatternElement element = create(factory(0.3, 1.0), ElementParity.NONE, false, true,
				ScaleTraversalStrategy.CHORD);

		for (ChannelInfo.Voicing voicing : ChannelInfo.Voicing.values()) {
			PatternNote note = element.getNote(voicing);
			Assert.assertTrue(voicing + " has the melodic batched shape",
					BatchedNoteInputs.isMelodicSssShape(note));

			PatternNote inner = (PatternNote) ((PatternNote) note.getDelegate()).getDelegate();
			for (PatternNoteAudio layer : inner.getLayers()) {
				Assert.assertTrue(layer instanceof PatternNoteLayer);
			}
		}

		Assert.assertEquals(PatternElementFactory.CHORD_STRATEGY, element.getDurationStrategy());
		Assert.assertEquals(ScaleTraversalStrategy.CHORD, element.getScaleTraversalStrategy());

		PatternElement sequence = create(factory(0.3, 1.0), ElementParity.NONE, false, true,
				ScaleTraversalStrategy.SEQUENCE);
		Assert.assertEquals(NoteDurationStrategy.FIXED, sequence.getDurationStrategy());
		Assert.assertEquals(ScaleTraversalStrategy.SEQUENCE, sequence.getScaleTraversalStrategy());
	}

	/** Chord positions are selected per depth, as magnitudes, up to the requested depth. */
	@Test(timeout = 30000)
	public void scalePositionsFollowChordSelection() {
		List<Double> positions = create(factory(0.3, 1.0), ElementParity.NONE, false, true,
				ScaleTraversalStrategy.CHORD).getScalePositions();

		Assert.assertEquals(2, positions.size());
		Assert.assertEquals(0.25, positions.get(0), EPSILON);
		Assert.assertEquals(0.75, positions.get(1), EPSILON);
	}

	/** Note length and repeat spacing are bounded by one measure and scaled by the length factor. */
	@Test(timeout = 30000)
	public void noteLengthAndRepeatSpacing() {
		PatternElementFactory factory = factory(0.3, 1.0);

		PatternElement wide = factory.apply(ElementParity.NONE, 2.0, 4.0, 0.5,
				ScaleTraversalStrategy.CHORD, 1, false, false, PARAMS).orElseThrow();
		Assert.assertEquals(PatternElementFactory.noteLengthFactor, wide.getNoteDurationSelection(), EPSILON);
		Assert.assertEquals(1.0, wide.getRepeatDuration(), 0.0);

		PatternElement narrow = factory.apply(ElementParity.NONE, 2.0, 0.5, 0.5,
				ScaleTraversalStrategy.CHORD, 1, false, false, PARAMS).orElseThrow();
		Assert.assertEquals(0.5 * PatternElementFactory.noteLengthFactor,
				narrow.getNoteDurationSelection(), EPSILON);
		Assert.assertEquals(0.5, narrow.getRepeatDuration(), 0.0);

		double previous = PatternElementFactory.noteLengthFactor;
		PatternElementFactory.noteLengthFactor = 0.5;
		try {
			PatternElement shortNotes = factory.apply(ElementParity.NONE, 2.0, 1.0, 0.5,
					ScaleTraversalStrategy.CHORD, 1, false, false, PARAMS).orElseThrow();
			Assert.assertEquals(0.5, shortNotes.getNoteDurationSelection(), EPSILON);
			Assert.assertEquals("short notes repeat twice as often", 0.5,
					shortNotes.getRepeatDuration(), 0.0);
		} finally {
			PatternElementFactory.noteLengthFactor = previous;
		}
	}

	/**
	 * The repeat count grows as the repeat selection shrinks, multiplying by 1.8
	 * until it reaches 3, and is capped at 6. Repetition is disabled when not
	 * requested or when the selection is not positive.
	 */
	@Test(timeout = 30000)
	public void repeatCountFollowsSelection() {
		ScaleTraversalStrategy chord = ScaleTraversalStrategy.CHORD;

		Assert.assertEquals("1.0 -> 1.8 -> 3.24", 2,
				create(factory(0.3, 1.0), ElementParity.NONE, true, false, chord).getRepeatCount());
		Assert.assertEquals("0.5 -> 0.9 -> 1.62 -> 2.916 -> 5.25", 4,
				create(factory(0.3, 0.5), ElementParity.NONE, true, false, chord).getRepeatCount());
		Assert.assertEquals("small selections are capped", 6,
				create(factory(0.3, 0.1), ElementParity.NONE, true, false, chord).getRepeatCount());
		Assert.assertEquals("negative selections do not repeat", 1,
				create(factory(0.3, -0.5), ElementParity.NONE, true, false, chord).getRepeatCount());
		Assert.assertEquals("repetition not requested", 1,
				create(factory(0.3, 0.1), ElementParity.NONE, false, false, chord).getRepeatCount());
	}

	/** When a repeat distribution is being recorded, each created element increments its bucket. */
	@Test(timeout = 30000)
	public void repeatDistributionIsRecorded() {
		int[] previous = PatternElementFactory.REPEAT_DIST;
		PatternElementFactory.REPEAT_DIST = new int[7];

		try {
			create(factory(0.3, 1.0), ElementParity.NONE, true, false, ScaleTraversalStrategy.CHORD);
			create(factory(0.3, 1.0), ElementParity.NONE, true, false, ScaleTraversalStrategy.CHORD);
			create(factory(0.3, 1.0), ElementParity.NONE, false, false, ScaleTraversalStrategy.CHORD);

			Assert.assertArrayEquals(new int[] { 1, 0, 2, 0, 0, 0, 0 }, PatternElementFactory.REPEAT_DIST);
		} finally {
			PatternElementFactory.REPEAT_DIST = previous;
		}
	}

	/** Re-initializing the selection functions replaces them with fresh random functions. */
	@Test(timeout = 30000)
	public void initSelectionFunctionsReplacesDeterministicFunctions() {
		PatternElementFactory factory = factory(0.3, 1.0);
		ParameterizedPositionFunction noteSelection = factory.getNoteSelection();
		factory.initSelectionFunctions();

		Assert.assertNotSame(noteSelection, factory.getNoteSelection());
		Assert.assertEquals(PatternElementFactory.MAX_LAYERS, factory.getNoteLayerSelections().size());
		Assert.assertEquals(ChordPositionFunction.MAX_CHORD_DEPTH,
				factory.getChordNoteSelection().getScalePositions().size());
		Assert.assertNotNull(factory.getVolumeEnvelope());
		Assert.assertNotNull(factory.getFilterEnvelope());
		Assert.assertNotNull(factory.getRepeatSelection());
		Assert.assertNotNull(factory.getNoteLengthSelection());

		PatternNoteFactory notes = new PatternNoteFactory();
		factory.setNoteFactory(notes);
		Assert.assertSame(notes, factory.getNoteFactory());
		factory.setVolumeEnvelope(null);
		factory.setFilterEnvelope(null);
		Assert.assertNull(factory.getVolumeEnvelope());
		Assert.assertNull(factory.getFilterEnvelope());
	}

	/** Unblended notes use plain selection layers; blended notes wrap each layer in an envelope. */
	@Test(timeout = 30000)
	public void noteFactoryBuildsOneLayerPerChoice() {
		PatternNoteFactory notes = new PatternNoteFactory();
		Assert.assertEquals(PatternNoteFactory.LAYER_COUNT, notes.getLayerCount());
		Assert.assertNotNull(notes.getLayerEnvelopes());

		PatternNote plain = notes.apply(PARAMS, ChannelInfo.Voicing.MAIN, false, 0.1, 0.5, 0.9);
		Assert.assertEquals(List.of(0.1, 0.5, 0.9), selections(plain));

		PatternNote blended = notes.apply(PARAMS, ChannelInfo.Voicing.MAIN, true, 0.1, 0.5, 0.9);
		Assert.assertEquals(3, blended.getLayers().size());
		blended.getLayers().forEach(l -> Assert.assertTrue(l instanceof PatternNoteLayer));

		try {
			notes.apply(PARAMS, ChannelInfo.Voicing.MAIN, false, 0.1);
			Assert.fail("one selection is required per layer");
		} catch (IllegalArgumentException expected) {
			Assert.assertEquals("Exactly one selection is required per layer", expected.getMessage());
			Assert.assertEquals(3, notes.getLayerCount());
		}

		try {
			notes.apply(PARAMS, ChannelInfo.Voicing.MAIN, false, 0.1, 0.5, 0.9, 0.3);
			Assert.fail("extra selections are rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertEquals("Exactly one selection is required per layer", expected.getMessage());
			Assert.assertEquals(3, notes.getLayerCount());
		}

		notes.setLayerEnvelopes(null);
		notes.setVolumeEnvelope(ParameterizedVolumeEnvelope.random(ParameterizedVolumeEnvelope.Mode.STANDARD_NOTE));
		Assert.assertNull(notes.getLayerEnvelopes());
		Assert.assertNotNull(notes.getVolumeEnvelope());
		Assert.assertNull(notes.getFilterEnvelope());
	}

	/** Seed scales are the duration times the granularity, clamped to the configured bounds. */
	@Test(timeout = 30000)
	public void seedScaleIsClamped() {
		PatternLayerSeeds seeds = new PatternLayerSeeds(0, 0.25, 0.5, 2.0, 0.0, null, PARAMS);
		Assert.assertEquals(1.0, seeds.getScale(4.0), 0.0);
		Assert.assertEquals("clamped to the maximum", 2.0, seeds.getScale(16.0), 0.0);
		Assert.assertEquals("clamped to the minimum", 0.5, seeds.getScale(1.0), 0.0);
		Assert.assertEquals("an explicit minimum raises the scale", 1.5, seeds.getScale(4.0, 1.5), 0.0);
		Assert.assertEquals("a lower explicit minimum has no effect", 1.0, seeds.getScale(4.0, 0.1), 0.0);

		PatternLayerSeeds defaults = new PatternLayerSeeds();
		Assert.assertEquals(0.0, defaults.getPosition(), 0.0);
		Assert.assertEquals(1.0, defaults.getGranularity(), 0.0);
		Assert.assertEquals(0.0, defaults.getBias(), 0.0);
		Assert.assertEquals(64.0, defaults.getScale(1000.0), 0.0);
		Assert.assertEquals(0.0625, defaults.getScale(0.001), 0.0);

		defaults.setPosition(3.0);
		defaults.setGranularity(0.5);
		defaults.setBias(0.25);
		Assert.assertEquals(3.0, defaults.getPosition(), 0.0);
		Assert.assertEquals(4.0, defaults.getScale(8.0), 0.0);
		Assert.assertEquals(0.25, defaults.getBias(), 0.0);
	}

	/**
	 * Seeds produce one single-element layer per scale step, covering the duration
	 * inclusively and starting at the seed position plus the offset.
	 */
	@Test(timeout = 30000)
	public void seedsGenerateOneLayerPerStep() {
		NoteAudioChoice choice = new NoteAudioChoice("perc");
		PatternElementFactory factory = factory(0.3, 1.0);
		PatternLayerSeeds seeds = new PatternLayerSeeds(0, 0.25, 0.0625, 64, 0.5, choice, PARAMS);

		List<PatternLayer> layers = seeds.generator(factory, 0.0, 4.0,
				ScaleTraversalStrategy.CHORD, 1, 0.0625).collect(Collectors.toList());
		Assert.assertEquals(List.of(0.0, 1.0, 2.0, 3.0, 4.0), firstPositions(layers));
		layers.forEach(l -> {
			Assert.assertSame(choice, l.getChoice());
			Assert.assertEquals(1, l.getElements().size());
		});

		Assert.assertEquals("offset and a larger minimum scale",
				List.of(8.0, 10.0, 12.0),
				firstPositions(seeds.generator(factory, 8.0, 4.0,
						ScaleTraversalStrategy.CHORD, 1, 2.0).collect(Collectors.toList())));

		PatternLayerSeeds silent = new PatternLayerSeeds(0, 0.25, 0.0625, 64, -0.5, choice, PARAMS);
		Assert.assertEquals(0, silent.generator(factory, 0.0, 4.0,
				ScaleTraversalStrategy.CHORD, 1, 0.0625).count());
	}

	/**
	 * Returns the position of the first element of each layer.
	 *
	 * @param layers the layers to inspect
	 * @return the first element positions
	 */
	private static List<Double> firstPositions(List<PatternLayer> layers) {
		return layers.stream().map(l -> l.getElements().get(0).getPosition()).collect(Collectors.toList());
	}
}
