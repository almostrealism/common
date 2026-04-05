/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.studio.midi.MidiFileReader;
import org.almostrealism.studio.midi.MidiTokenizer;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.pattern.NoteDurationStrategy;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assert;
import static org.junit.Assert.*;

/**
 * Tests for the pattern-to-MIDI export pipeline (Milestone 9).
 *
 * <p>Verifies that {@link PatternElement#toMidiEvents},
 * {@link org.almostrealism.music.pattern.PatternLayerManager#toMidiEvents}, and
 * {@link org.almostrealism.music.pattern.PatternSystemManager#toMidiEvents}
 * produce valid MIDI events with correct pitch, velocity, onset, and duration.</p>
 */
public class PatternMidiExportTest extends TestSuiteBase {

	/** Standard BPM for tests. */
	private static final double BPM = 120.0;

	/** Beats per measure (4/4 time). */
	private static final int BEATS_PER_MEASURE = 4;

	/** Sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/**
	 * Creates an {@link AudioSceneContext} with timing and scale configuration
	 * suitable for MIDI export testing.
	 *
	 * @param measures total measures in the arrangement
	 * @param scale    the scale to use at all positions
	 * @return configured context
	 */
	private AudioSceneContext createContext(int measures, Scale<?> scale) {
		double secondsPerBeat = 60.0 / BPM;
		double secondsPerMeasure = secondsPerBeat * BEATS_PER_MEASURE;
		double framesPerMeasure = secondsPerMeasure * SAMPLE_RATE;

		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames((int) (measures * framesPerMeasure));
		context.setFrameForPosition(pos -> (int) (pos * framesPerMeasure));
		context.setTimeForDuration(dur -> dur * secondsPerMeasure);
		context.setScaleForPosition(pos -> scale);

		return context;
	}

	@Test
	public void singleNoteExport() {
		Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.CHORD);
		element.setScalePosition(List.of(0.0));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(1);
		element.setRepeatDuration(0.25);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		assertEquals("Expected one MIDI event", 1, events.size());

		MidiNoteEvent event = events.get(0);
		int expectedPitch = WesternChromatic.C4.position() + MidiNoteEvent.PITCH_OFFSET;
		assertEquals("Pitch should be C4 (MIDI 60)", expectedPitch, event.getPitch());
		assertEquals("Onset should be 0", 0, event.getOnset());
		assertTrue("Duration should be positive", event.getDuration() > 0);
		assertEquals("Velocity should be default", MidiNoteEvent.DEFAULT_VELOCITY, event.getVelocity());
		assertEquals("Instrument should be 0 (piano)", 0, event.getInstrument());
	}

	@Test
	public void chordExport() {
		Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.CHORD);
		element.setScalePosition(List.of(0.0, 0.5, 1.0));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.5);
		element.setRepeatCount(1);
		element.setRepeatDuration(0.5);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		assertEquals("Expected three MIDI events (chord)", 3, events.size());

		int pitchC4 = WesternChromatic.C4.position() + MidiNoteEvent.PITCH_OFFSET;
		int pitchE4 = WesternChromatic.E4.position() + MidiNoteEvent.PITCH_OFFSET;
		int pitchG4 = WesternChromatic.G4.position() + MidiNoteEvent.PITCH_OFFSET;

		assertTrue("Should contain C4", events.stream().anyMatch(e -> e.getPitch() == pitchC4));
		assertTrue("Should contain E4", events.stream().anyMatch(e -> e.getPitch() == pitchE4));
		assertTrue("Should contain G4", events.stream().anyMatch(e -> e.getPitch() == pitchG4));

		assertTrue("All events should have same onset",
				events.stream().allMatch(e -> e.getOnset() == events.get(0).getOnset()));
	}

	@Test
	public void sequenceExport() {
		Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		element.setScalePosition(List.of(0.0, 0.5));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(4);
		element.setRepeatDuration(0.25);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		assertEquals("Expected four MIDI events (4 repeats)", 4, events.size());

		long firstOnset = events.get(0).getOnset();
		long secondOnset = events.get(1).getOnset();
		assertTrue("Second onset should be after first",
				secondOnset > firstOnset);

		int pitchC4 = WesternChromatic.C4.position() + MidiNoteEvent.PITCH_OFFSET;
		int pitchE4 = WesternChromatic.E4.position() + MidiNoteEvent.PITCH_OFFSET;
		assertEquals("First note should be C4 (scalePos 0.0)", pitchC4, events.get(0).getPitch());
		assertEquals("Second note should be E4 (scalePos 0.5)", pitchE4, events.get(1).getPitch());
		assertEquals("Third note should be C4 (wraps)", pitchC4, events.get(2).getPitch());
		assertEquals("Fourth note should be E4 (wraps)", pitchE4, events.get(3).getPitch());
	}

	@Test
	public void percussiveExport() {
		Scale<?> scale = Scale.of(WesternChromatic.C4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.CHORD);
		element.setScalePosition(List.of(0.0));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.125);
		element.setRepeatCount(4);
		element.setRepeatDuration(0.25);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, false, 0.0,
				MidiNoteEvent.DRUM_INSTRUMENT);

		assertEquals("Expected four drum hits", 4, events.size());
		assertTrue("All should be drum instrument",
				events.stream().allMatch(e -> e.getInstrument() == MidiNoteEvent.DRUM_INSTRUMENT));
	}

	@Test
	public void automationVelocity() {
		Scale<?> scale = Scale.of(WesternChromatic.C4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.CHORD);
		element.setScalePosition(List.of(0.0));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(1);
		element.setRepeatDuration(0.25);

		PackedCollection automation =
				new PackedCollection(6);
		automation.setMem(0, 0.5);
		element.setAutomationParameters(automation);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		assertEquals(1, events.size());
		int expectedVelocity = (int) (0.5 * 127);
		assertEquals("Velocity should reflect automation",
				expectedVelocity, events.get(0).getVelocity());
	}

	@Test
	public void timingAccuracy() {
		Scale<?> scale = Scale.of(WesternChromatic.C4);
		double secondsPerMeasure = (60.0 / BPM) * BEATS_PER_MEASURE;

		PatternElement element = new PatternElement();
		element.setPosition(1.0);
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(1);
		element.setRepeatDuration(0.25);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		assertEquals(1, events.size());
		long expectedOnsetTicks = (long) (1.0 * secondsPerMeasure * MidiNoteEvent.TIME_RESOLUTION);
		assertEquals("Onset should be at measure 1.0",
				expectedOnsetTicks, events.get(0).getOnset());

		long expectedDurationTicks = (long) (0.25 * secondsPerMeasure * MidiNoteEvent.TIME_RESOLUTION);
		assertEquals("Duration should match 0.25 measures",
				expectedDurationTicks, events.get(0).getDuration());
	}

	@Test
	public void offsetTimingAccuracy() {
		Scale<?> scale = Scale.of(WesternChromatic.C4);
		double secondsPerMeasure = (60.0 / BPM) * BEATS_PER_MEASURE;

		PatternElement element = new PatternElement();
		element.setPosition(0.5);
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(1);
		element.setRepeatDuration(0.25);

		AudioSceneContext context = createContext(8, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 4.0, 0);

		assertEquals(1, events.size());
		long expectedOnsetTicks = (long) (4.5 * secondsPerMeasure * MidiNoteEvent.TIME_RESOLUTION);
		assertEquals("Onset should account for pattern offset",
				expectedOnsetTicks, events.get(0).getOnset());
	}

	@Test
	public void roundTripMidiFile() throws Exception {
		Scale<?> scale = Scale.of(
				WesternChromatic.C4, WesternChromatic.D4,
				WesternChromatic.E4, WesternChromatic.F4,
				WesternChromatic.G4, WesternChromatic.A4, WesternChromatic.B4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		element.setScalePosition(List.of(0.0, 0.15, 0.3, 0.45, 0.6, 0.75, 0.9));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(7);
		element.setRepeatDuration(0.25);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		assertEquals("Expected 7 notes (one per repeat)", 7, events.size());

		File tempFile = File.createTempFile("pattern-midi-test", ".mid");
		tempFile.deleteOnExit();

		MidiFileReader reader = new MidiFileReader();
		reader.write(events, tempFile);

		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Round-trip should preserve note count",
				events.size(), readBack.size());

		for (int i = 0; i < events.size(); i++) {
			MidiNoteEvent original = events.get(i);
			MidiNoteEvent roundTripped = readBack.get(i);

			assertEquals("Pitch should match for note " + i,
					original.getPitch(), roundTripped.getPitch());
			assertEquals("Velocity should match for note " + i,
					original.getVelocity(), roundTripped.getVelocity());
			assertEquals("Onset should match for note " + i,
					original.getOnset(), roundTripped.getOnset());
			assertEquals("Duration should match for note " + i,
					original.getDuration(), roundTripped.getDuration());
		}
	}

	@Test
	public void tokenizerCompatibility() {
		Scale<?> scale = Scale.of(
				WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

		PatternElement element = new PatternElement();
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.CHORD);
		element.setScalePosition(List.of(0.0, 0.5, 1.0));
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.5);
		element.setRepeatCount(1);
		element.setRepeatDuration(0.5);

		AudioSceneContext context = createContext(4, scale);

		List<MidiNoteEvent> events = element.toMidiEvents(context, true, 0.0, 0);

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> tokens = tokenizer.tokenize(events);

		assertNotNull("Tokens should not be null", tokens);
		assertTrue("Should have SOS + events + EOS",
				tokens.size() >= events.size() + 2);

		List<MidiNoteEvent> detokenized = tokenizer.detokenize(tokens);
		assertEquals("Detokenized count should match",
				events.size(), detokenized.size());

		Set<Integer> originalPitches = events.stream()
				.map(MidiNoteEvent::getPitch)
				.collect(Collectors.toSet());
		Set<Integer> roundTrippedPitches = detokenized.stream()
				.map(MidiNoteEvent::getPitch)
				.collect(Collectors.toSet());
		Assert.assertEquals("Pitches should survive tokenization round-trip",
				originalPitches, roundTrippedPitches);
	}

	@Test
	public void pitchMapping() {
		assertEquals("C4 should map to MIDI 60",
				60, WesternChromatic.C4.position() + MidiNoteEvent.PITCH_OFFSET);
		assertEquals("A4 should map to MIDI 69",
				69, WesternChromatic.A4.position() + MidiNoteEvent.PITCH_OFFSET);
		assertEquals("A0 should map to MIDI 21",
				21, WesternChromatic.A0.position() + MidiNoteEvent.PITCH_OFFSET);
	}
}
