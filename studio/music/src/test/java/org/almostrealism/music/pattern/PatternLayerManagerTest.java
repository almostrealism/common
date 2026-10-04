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

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tests for the structural behaviour of {@link PatternLayerManager}: its settings,
 * the explicit-element path, note spacing, MIDI export, and the deterministic
 * growth of its layer hierarchy when every selection function is fixed.
 */
public class PatternLayerManagerTest extends TestSuiteBase implements AudioTestFeatures {

	/**
	 * Creates a manager on channel 0 over the given choices.
	 *
	 * @param choices  the available note choices
	 * @param measures the pattern duration
	 * @param melodic  whether the pattern is melodic
	 * @return the manager
	 */
	private static PatternLayerManager manager(List<NoteAudioChoice> choices, double measures,
											   boolean melodic) {
		return new PatternLayerManager(choices, new ProjectedGenome(8).addChromosome(),
				0, measures, melodic);
	}

	/**
	 * Creates a percussive element with a three-layer note at the given position.
	 *
	 * @param position    the position in measures
	 * @param repetitions the repeat count
	 * @return the element
	 */
	private static PatternElement hit(double position, int repetitions) {
		PatternElement element = new PatternElement(new PatternNote(0.1, 0.5, 0.9), position);
		element.setRepeatCount(repetitions);
		element.setRepeatDuration(0.5);
		return element;
	}

	/**
	 * Creates a percussive choice backed by a short generated sample on channel 0.
	 *
	 * @return the choice
	 */
	private NoteAudioChoice percussion() {
		NoteAudioChoice choice = NoteAudioChoice.fromSource("Percussion",
				new FileNoteSource(getNamedTestWavPath("plm_hit.wav", 220.0, 0.25, true),
						WesternChromatic.C1), 0, 9, false);
		choice.setTuning(new DefaultKeyboardTuning());
		return choice;
	}

	/**
	 * Returns the positions of the given elements, sorted.
	 *
	 * @param elements the elements
	 * @return sorted positions
	 */
	private static List<Double> sortedPositions(List<PatternElement> elements) {
		return elements.stream().map(PatternElement::getPosition).sorted().collect(Collectors.toList());
	}

	/** A new manager uses the documented defaults and reports them through its settings. */
	@Test(timeout = 60000)
	public void settingsReflectConfiguration() {
		PatternLayerManager plm = manager(List.of(), 4.0, true);

		PatternLayerManager.Settings settings = plm.getSettings();
		Assert.assertEquals(0, settings.getChannel());
		Assert.assertEquals(4.0, settings.getDuration(), 0.0);
		Assert.assertTrue(settings.isMelodic());
		Assert.assertEquals(ScaleTraversalStrategy.CHORD, settings.getScaleTraversalStrategy());
		Assert.assertEquals(1, settings.getScaleTraversalDepth());
		Assert.assertEquals(0.0625, settings.getMinLayerScale(), 0.0);
		Assert.assertEquals(0, settings.getLayerCount());
		Assert.assertSame(plm.getElementFactory(), settings.getElementFactory());
		Assert.assertNotNull(settings.getFactorySelection());
		Assert.assertNotNull(settings.getActiveSelection());
		Assert.assertEquals(1.0, plm.getSeedBiasAdjustment(), 0.0);
	}

	/** Applying settings replaces the configuration; absent selection functions are kept. */
	@Test(timeout = 60000)
	public void settingsAreApplied() {
		PatternLayerManager plm = manager(List.of(), 4.0, true);
		PatternElementFactory originalFactory = plm.getElementFactory();

		PatternLayerManager.Settings settings = new PatternLayerManager.Settings();
		settings.setChannel(3);
		settings.setDuration(8.0);
		settings.setMelodic(false);
		settings.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		settings.setScaleTraversalDepth(4);
		settings.setMinLayerScale(0.5);
		plm.setSettings(settings);

		Assert.assertEquals(3, plm.getChannel());
		Assert.assertEquals(8.0, plm.getDuration(), 0.0);
		Assert.assertFalse(plm.isMelodic());
		Assert.assertEquals(ScaleTraversalStrategy.SEQUENCE, plm.getScaleTraversalStrategy());
		Assert.assertEquals(4, plm.getScaleTraversalDepth());
		Assert.assertEquals(0.5, plm.getMinLayerScale(), 0.0);
		Assert.assertSame("a null factory in the settings keeps the current one",
				originalFactory, plm.getElementFactory());

		PatternElementFactory factory = new PatternElementFactory();
		settings.setElementFactory(factory);
		plm.setSettings(settings);
		Assert.assertSame(factory, plm.getElementFactory());

		settings.setLayers(List.of(new ParameterSet(),
				new ParameterSet()));
		Assert.assertEquals(2, settings.getLayerCount());
		settings.setLayers(null);
		Assert.assertEquals("null layers leave the count unchanged", 2, settings.getLayerCount());
	}

	/** A negative layer count is rejected without changing the manager. */
	@Test(timeout = 60000)
	public void negativeLayerCountIsRejected() {
		PatternLayerManager plm = manager(List.of(), 4.0, false);

		try {
			plm.setLayerCount(-1);
			Assert.fail("negative layer counts are invalid");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage().contains("-1"));
		}

		Assert.assertEquals(0, plm.getLayerCount());
		Assert.assertEquals(0, plm.depth());
	}

	/**
	 * Explicit elements replace the hierarchy with a single root owned by the given
	 * choice, copied so later changes to the caller's list have no effect.
	 */
	@Test(timeout = 60000)
	public void explicitElementsFormSingleRoot() {
		PatternLayerManager plm = manager(List.of(), 4.0, false);
		NoteAudioChoice choice = new NoteAudioChoice("explicit");
		List<PatternElement> elements = new ArrayList<>(List.of(hit(0.0, 1), hit(1.0, 2), hit(3.0, 1)));

		plm.setExplicitElements(choice, elements);
		elements.clear();

		Assert.assertEquals(1, plm.getLayerCount());
		Assert.assertEquals(1, plm.rootCount());
		Assert.assertEquals(1, plm.depth());
		Assert.assertEquals(List.of(0.0, 1.0), sortedPositions(plm.getAllElements(0.0, 2.0)));

		Map<NoteAudioChoice, List<PatternElement>> byChoice = plm.getAllElementsByChoice(0.0, 4.0);
		Assert.assertEquals(1, byChoice.size());
		Assert.assertEquals(3, byChoice.get(choice).size());

		plm.clear();
		Assert.assertEquals(0, plm.depth());
		Assert.assertTrue(plm.getAllElements(0.0, 4.0).isEmpty());
	}

	/**
	 * The next note position is the earliest later onset among elements with an
	 * onset at or after the query, including their repetitions, or the pattern
	 * duration when no later note exists.
	 */
	@Test(timeout = 60000)
	public void nextNotePositionIncludesRepetitions() {
		PatternLayerManager plm = manager(List.of(), 4.0, false);
		plm.setExplicitElements(new NoteAudioChoice("explicit"),
				List.of(hit(0.0, 1), hit(1.0, 2), hit(3.0, 1)));

		Assert.assertEquals(1.0, plm.nextNotePosition(0.0), 0.0);
		Assert.assertEquals("the repetition of the element at 1.0", 1.5, plm.nextNotePosition(1.0), 0.0);
		Assert.assertEquals(3.0, plm.nextNotePosition(1.5), 0.0);
		Assert.assertEquals("nothing follows the last note", 4.0, plm.nextNotePosition(3.0), 0.0);
	}

	/**
	 * A repetition is found even when the element's base position precedes the
	 * query: from {@code 1.2}, the element at {@code 1.0} repeating at {@code 1.5}
	 * supplies the next onset, rather than the following element at {@code 3.0}.
	 */
	@Test(timeout = 60000)
	public void nextNotePositionFindsRepetitionOfEarlierElement() {
		PatternLayerManager plm = manager(List.of(), 4.0, false);
		plm.setExplicitElements(new NoteAudioChoice("explicit"),
				List.of(hit(0.0, 1), hit(1.0, 2), hit(3.0, 1)));

		Assert.assertEquals(1.5, plm.nextNotePosition(1.2), 0.0);
		Assert.assertEquals(3.0, plm.nextNotePosition(1.6), 0.0);
	}

	/**
	 * MIDI export emits one event per repetition of each element within the pattern,
	 * sorted by onset, on the drum instrument for percussive patterns, and shifted
	 * by the requested offset.
	 */
	@Test(timeout = 60000)
	public void midiExportCoversPatternDuration() {
		PatternLayerManager plm = manager(List.of(), 2.0, false);
		plm.setExplicitElements(new NoteAudioChoice("explicit"),
				List.of(hit(1.0, 2), hit(0.0, 1), hit(2.5, 1)));

		int measureFrames = 2 * OutputLine.sampleRate;
		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(4);
		context.setFrames(4 * measureFrames);
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(d -> d * 2.0);
		context.setScaleForPosition(pos -> Scale.of(WesternChromatic.C4));

		List<MidiNoteEvent> events = plm.toMidiEvents(context);
		Assert.assertEquals("the element at 2.5 lies beyond the 2-measure pattern",
				List.of(0L, 200L, 300L),
				events.stream().map(MidiNoteEvent::getOnset).collect(Collectors.toList()));
		events.forEach(e -> {
			Assert.assertEquals(60, e.getPitch());
			Assert.assertEquals(MidiNoteEvent.DRUM_INSTRUMENT, e.getInstrument());
		});

		Assert.assertEquals(List.of(400L, 600L, 700L),
				plm.toMidiEvents(context, 2.0).stream().map(MidiNoteEvent::getOnset)
						.collect(Collectors.toList()));
	}

	/**
	 * Without any usable note choices, each requested layer still adds a level to
	 * the hierarchy (with no elements), and lowering the layer count removes levels.
	 */
	@Test(timeout = 120000)
	public void layersWithoutChoicesAreEmpty() {
		PatternLayerManager plm = manager(List.of(new NoteAudioChoice("no sources")), 4.0, false);
		Assert.assertTrue("choices without valid notes are filtered out", plm.getChoices().isEmpty());
		Assert.assertNull(plm.choose(1.0, new ParameterSet()));
		Assert.assertNull(plm.getSeeds(new ParameterSet()));

		plm.setLayerCount(2);
		Assert.assertEquals(2, plm.depth());
		Assert.assertEquals(1, plm.rootCount());
		Assert.assertTrue(plm.getAllElements(0.0, 4.0).isEmpty());

		plm.setLayerCount(1);
		Assert.assertEquals(1, plm.depth());

		plm.setLayerCount(0);
		Assert.assertEquals(0, plm.depth());
		Assert.assertEquals(0, plm.rootCount());
	}

	/**
	 * With every selection function fixed, the first layer seeds one root per
	 * measure and the second layer adds a note half a measure either side of every
	 * seed, discarding those before the start of the pattern. Every element carries
	 * the automation parameters of its layer.
	 */
	@Test(timeout = 180000)
	public void layersGrowDeterministically() {
		NoteAudioChoice choice = percussion();
		choice.setBias(0.5);
		choice.setGranularitySelection(ParameterizedPositionFunctionTest.constantFunction(0.5));

		PatternLayerManager plm = manager(List.of(choice), 4.0, false);
		Assert.assertEquals(List.of(choice), plm.getChoices());

		PatternElementFactory factory = new PatternElementFactory();
		factory.setNoteSelection(ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.3));
		factory.setNoteLayerSelections(List.of(
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.1),
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.2),
				ParameterizedPositionFunctionTest.constantAtWholeMeasures(0.3)));
		plm.setElementFactory(factory);

		plm.setLayerCount(1);
		Assert.assertEquals(5, plm.rootCount());
		Assert.assertEquals("one seed per measure; the seed at 4.0 lies outside the pattern",
				List.of(0.0, 1.0, 2.0, 3.0), sortedPositions(plm.getAllElements(0.0, 4.0)));

		plm.setLayerCount(2);
		Assert.assertEquals(2, plm.depth());
		Assert.assertEquals(5, plm.rootCount());
		Assert.assertEquals(List.of(0.0, 0.5, 0.5, 1.0, 1.5, 1.5, 2.0, 2.5, 2.5, 3.0, 3.5, 3.5),
				sortedPositions(plm.getAllElements(0.0, 4.0)));

		plm.getAllElements(0.0, 4.0).forEach(e -> {
			Assert.assertNotNull(e.getAutomationParameters());
			Assert.assertEquals(PatternLayerManager.AUTOMATION_GENE_LENGTH,
					e.getAutomationParameters().getMemLength());
		});

		plm.removeLayer();
		Assert.assertEquals(1, plm.depth());
		Assert.assertEquals(4, plm.getAllElements(0.0, 4.0).size());
	}

	/** Channel and destination bookkeeping only registers the manager's own channel. */
	@Test(timeout = 60000)
	public void destinationRegistersOwnChannelOnly() {
		PatternLayerManager plm = manager(List.of(), 4.0, false);
		plm.setChannel(2);

		AudioSceneContext context = new AudioSceneContext();
		plm.updateDestination(context);
		Assert.assertNull("no channels means nothing is registered", plm.getDestination());

		PackedCollection destination = new PackedCollection(16);
		context.setDestination(destination);
		context.setChannels(List.of(
				new ChannelInfo(2, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT),
				new ChannelInfo(2, ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.RIGHT),
				new ChannelInfo(5, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT)));
		plm.updateDestination(context);

		Assert.assertEquals(2, plm.getDestination().size());
		Assert.assertSame(destination, plm.getDestination().get(
				new ChannelInfo(ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.RIGHT)));
		Assert.assertNull(plm.getDestination().get(
				new ChannelInfo(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.RIGHT)));

		BatchedPatternLayerRenderer renderer = plm.getBatchedLayerRenderer();
		Assert.assertSame("the batched renderer is created once", renderer, plm.getBatchedLayerRenderer());
		Assert.assertEquals(OutputLine.sampleRate, renderer.getSampleRate());
	}
}
