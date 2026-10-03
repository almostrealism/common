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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests for how {@link ScaleTraversalStrategy} turns a {@link PatternElement} into
 * MIDI events and render destinations. Timing uses two seconds per measure, so a
 * measure spans 200 MIDI ticks and {@code 2 * sampleRate} audio frames.
 */
public class ScaleTraversalMidiTest extends TestSuiteBase {

	/** Seconds per measure used by the scene context. */
	private static final double MEASURE_SECONDS = 2.0;

	/** Frames of synthetic source audio held by the test note. */
	private static final int SOURCE_FRAMES = 1024;

	/** C major triad: MIDI pitches 60, 64 and 67. */
	private static final Scale<WesternChromatic> TRIAD =
			Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

	/**
	 * Creates an element with a synthetic main note, a fixed quarter-measure note
	 * duration and repetitions a quarter measure apart.
	 *
	 * @param strategy       the traversal strategy
	 * @param scalePositions the scale positions
	 * @param repetitions    the repeat count
	 * @return the element
	 */
	private PatternElement element(ScaleTraversalStrategy strategy, List<Double> scalePositions,
								   int repetitions) {
		NoteAudioProvider audio = NoteAudioProvider.create(
				() -> new PackedCollection(SOURCE_FRAMES), WesternChromatic.C4);
		audio.setTuning(new DefaultKeyboardTuning());

		PatternElement e = new PatternElement(new PatternNote(new SimplePatternNote(audio), null), 0.0);
		e.setScaleTraversalStrategy(strategy);
		e.setScalePosition(scalePositions);
		e.setRepeatCount(repetitions);
		e.setRepeatDuration(0.25);
		e.setDurationStrategy(NoteDurationStrategy.FIXED);
		e.setNoteDurationSelection(0.25);
		return e;
	}

	/**
	 * Creates a scene context that uses the given scale at every position.
	 *
	 * @param scale the scale
	 * @return the context
	 */
	private AudioSceneContext scene(Scale<?> scale) {
		int measureFrames = (int) (MEASURE_SECONDS * OutputLine.sampleRate);
		AudioSceneContext scene = new AudioSceneContext();
		scene.setMeasures(8);
		scene.setFrames(8 * measureFrames);
		scene.setFrameForPosition(position -> (int) (position * measureFrames));
		scene.setTimeForDuration(measures -> measures * MEASURE_SECONDS);
		scene.setScaleForPosition(position -> scale);
		return scene;
	}

	/**
	 * Returns the MIDI pitch of each event, in order.
	 *
	 * @param events the events to inspect
	 * @return the pitches
	 */
	private static List<Integer> pitches(List<MidiNoteEvent> events) {
		return events.stream().map(MidiNoteEvent::getPitch).collect(Collectors.toList());
	}

	/**
	 * Returns the onset tick of each event, in order.
	 *
	 * @param events the events to inspect
	 * @return the onsets
	 */
	private static List<Long> onsets(List<MidiNoteEvent> events) {
		return events.stream().map(MidiNoteEvent::getOnset).collect(Collectors.toList());
	}

	/**
	 * A chord emits one event per scale position for every repetition. Each key is
	 * consumed as it is used, so later positions index into the remaining keys, and
	 * the boundary position {@code 1.0} selects the last remaining key.
	 */
	@Test(timeout = 30000)
	public void chordEventsConsumeKeys() {
		List<MidiNoteEvent> events = element(ScaleTraversalStrategy.CHORD,
				List.of(0.0, 0.5, 1.0), 2).toMidiEvents(scene(TRIAD), true, 1.0, 0);

		Assert.assertEquals("C4, then G4 (index 1 of [E4, G4]), then E4",
				List.of(60, 67, 64, 60, 67, 64), pitches(events));
		Assert.assertEquals("measures 1.0 and 1.25",
				List.of(200L, 200L, 200L, 250L, 250L, 250L), onsets(events));

		long halfSecond = MidiNoteEvent.TIME_RESOLUTION / 2;
		for (MidiNoteEvent e : events) {
			Assert.assertEquals(halfSecond, e.getDurationTicks());
			Assert.assertEquals(MidiNoteEvent.DEFAULT_VELOCITY, e.getVelocity());
		}
	}

	/** A sequence emits one event per repetition, cycling through the scale positions. */
	@Test(timeout = 30000)
	public void sequenceEventsCyclePositions() {
		List<MidiNoteEvent> events = element(ScaleTraversalStrategy.SEQUENCE,
				List.of(0.0, 1.0), 3).toMidiEvents(scene(TRIAD), true, 0.0, 0);

		Assert.assertEquals(List.of(60, 67, 60), pitches(events));
		Assert.assertEquals(List.of(0L, 50L, 100L), onsets(events));
	}

	/**
	 * Percussive material emits a single event per repetition at the first key of
	 * the scale regardless of how many scale positions it carries. Without keys it
	 * falls back to pitch zero, whereas melodic material emits nothing.
	 */
	@Test(timeout = 30000)
	public void percussiveEventsUseFirstKey() {
		PatternElement e = element(ScaleTraversalStrategy.CHORD, List.of(0.0, 0.9), 2);
		Scale<WesternChromatic> upper = Scale.of(WesternChromatic.E4, WesternChromatic.G4);
		Scale<WesternChromatic> empty = Scale.of();

		Assert.assertEquals(List.of(64, 64),
				pitches(e.toMidiEvents(scene(upper), false, 0.0, MidiNoteEvent.DRUM_INSTRUMENT)));
		Assert.assertEquals(List.of(0, 0),
				pitches(e.toMidiEvents(scene(empty), false, 0.0, MidiNoteEvent.DRUM_INSTRUMENT)));
		Assert.assertTrue(e.toMidiEvents(scene(empty), true, 0.0, 0).isEmpty());

		e.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		Assert.assertTrue(e.toMidiEvents(scene(empty), true, 0.0, 0).isEmpty());
	}

	/**
	 * Velocity is taken from the first automation parameter when it lies in
	 * {@code (0, 1]}, never drops below one, and otherwise falls back to the
	 * default velocity.
	 */
	@Test(timeout = 30000)
	public void velocityFollowsAutomation() {
		PatternElement e = element(ScaleTraversalStrategy.CHORD, List.of(0.0), 1);

		Assert.assertEquals(63, velocity(e, PackedCollection.of(0.5, 0.2)));
		Assert.assertEquals("very quiet notes still sound", 1, velocity(e, PackedCollection.of(0.001, 0.2)));
		Assert.assertEquals(127, velocity(e, PackedCollection.of(1.0, 0.2)));
		Assert.assertEquals(MidiNoteEvent.DEFAULT_VELOCITY, velocity(e, PackedCollection.of(1.5, 0.2)));
		Assert.assertEquals(MidiNoteEvent.DEFAULT_VELOCITY, velocity(e, PackedCollection.of(0.0, 0.2)));
	}

	/**
	 * Returns the velocity of the first MIDI event of the element under the given
	 * automation parameters.
	 *
	 * @param element    the element
	 * @param automation the automation parameters to apply
	 * @return the velocity
	 */
	private int velocity(PatternElement element, PackedCollection automation) {
		element.setAutomationParameters(automation);
		return element.toMidiEvents(scene(TRIAD), true, 0.0, 0).get(0).getVelocity();
	}

	/**
	 * Notes without a fixed duration last for their repeat spacing, and every note
	 * lasts at least one tick.
	 */
	@Test(timeout = 30000)
	public void durationFollowsStrategy() {
		PatternElement e = element(ScaleTraversalStrategy.CHORD, List.of(0.0), 1);
		e.setRepeatDuration(0.5);
		long oneSecond = MidiNoteEvent.TIME_RESOLUTION;

		e.setDurationStrategy(NoteDurationStrategy.NONE);
		Assert.assertEquals(oneSecond, e.toMidiEvents(scene(TRIAD), true, 0.0, 0).get(0).getDurationTicks());

		e.setDurationStrategy(NoteDurationStrategy.NO_OVERLAP);
		Assert.assertEquals(oneSecond, e.toMidiEvents(scene(TRIAD), true, 0.0, 0).get(0).getDurationTicks());

		e.setDurationStrategy(NoteDurationStrategy.FIXED);
		e.setNoteDurationSelection(0.0001);
		Assert.assertEquals(1L, e.toMidiEvents(scene(TRIAD), true, 0.0, 0).get(0).getDurationTicks());
	}

	/**
	 * Rendering percussive material produces one destination per repetition, each
	 * offset by its repetition's frame position and sized by the note's natural
	 * duration, with a single-value offset argument for streaming playback. With
	 * batching disabled no batched inputs are gathered.
	 */
	@Test(timeout = 120000)
	public void percussiveDestinationsFollowRepetitions() {
		boolean batched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			PatternElement e = element(ScaleTraversalStrategy.CHORD, List.of(0.0), 3);
			e.setDurationStrategy(NoteDurationStrategy.NONE);
			e.setPosition(0.5);

			PatternNote note = e.getNote(ChannelInfo.Voicing.MAIN);
			NoteAudioContext audio = new NoteAudioContext(ChannelInfo.Voicing.MAIN,
					ChannelInfo.StereoChannel.LEFT, d -> note, p -> p + 0.25);

			List<RenderedNoteAudio> destinations = e.getNoteDestinations(false, 1.0, scene(TRIAD), audio);

			double measureFrames = MEASURE_SECONDS * OutputLine.sampleRate;
			Assert.assertEquals(e.getRepeatCount(), destinations.size());
			for (int i = 0; i < destinations.size(); i++) {
				RenderedNoteAudio rendered = destinations.get(i);
				Assert.assertEquals((int) ((1.5 + 0.25 * i) * measureFrames), rendered.getOffset());
				Assert.assertTrue("expected about " + SOURCE_FRAMES + " frames, got "
								+ rendered.getExpectedFrameCount(),
						Math.abs(SOURCE_FRAMES - rendered.getExpectedFrameCount()) <= 1);
				Assert.assertEquals(1, rendered.getOffsetArg().getMemLength());
				Assert.assertNull(rendered.getBatchedInputs());
			}
		} finally {
			PatternLayerManager.enableBatched = batched;
		}
	}

	/** A chord with more positions than keys stops once every key has been used. */
	@Test(timeout = 120000)
	public void chordDestinationsStopWhenKeysAreExhausted() {
		boolean batched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			PatternElement e = element(ScaleTraversalStrategy.CHORD, List.of(0.0, 0.0, 0.0, 0.0), 1);
			PatternNote note = e.getNote(ChannelInfo.Voicing.MAIN);
			NoteAudioContext audio = new NoteAudioContext(ChannelInfo.Voicing.MAIN,
					ChannelInfo.StereoChannel.RIGHT, d -> note, p -> p + 0.25);

			Assert.assertEquals(TRIAD.length(),
					e.getNoteDestinations(true, 0.0, scene(TRIAD), audio).size());
		} finally {
			PatternLayerManager.enableBatched = batched;
		}
	}
}
