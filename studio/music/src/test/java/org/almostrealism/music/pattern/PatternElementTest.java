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
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Tests for the timing and voicing contract of {@link PatternElement} and the
 * duration arithmetic of {@link NoteDurationStrategy}. None of these tests
 * evaluate audio; they pin the pure scheduling logic that decides where and for
 * how long a note plays.
 */
public class PatternElementTest extends TestSuiteBase {

	/** Converts measures to seconds at two seconds per measure (120 BPM, 4/4). */
	private static final DoubleUnaryOperator TWO_SECONDS_PER_MEASURE = m -> m * 2.0;

	/** A new element uses the documented defaults for every scheduling property. */
	@Test(timeout = 10000)
	public void defaultsMatchDocumentedValues() {
		PatternElement element = new PatternElement();

		Assert.assertEquals(0.0, element.getPosition(), 0.0);
		Assert.assertTrue("no note should be registered for a null main note",
				element.getNotes().isEmpty());
		Assert.assertEquals(NoteDurationStrategy.NONE, element.getDurationStrategy());
		Assert.assertEquals(ScaleTraversalStrategy.CHORD, element.getScaleTraversalStrategy());
		Assert.assertEquals(List.of(0.0), element.getScalePositions());
		Assert.assertEquals(PatternDirection.FORWARD, element.getDirection());
		Assert.assertEquals(1, element.getRepeatCount());
		Assert.assertEquals(1.0, element.getRepeatDuration(), 0.0);
		Assert.assertNull(element.getAutomationParameters());
	}

	/** The main/wet constructor registers each non-null note under its voicing. */
	@Test(timeout = 10000)
	public void mainAndWetNotesAreRegisteredByVoicing() {
		PatternNote main = new PatternNote(0.1);
		PatternNote wet = new PatternNote(0.9);

		PatternElement both = new PatternElement(main, wet, 2.5);
		Assert.assertEquals(2.5, both.getPosition(), 0.0);
		Assert.assertSame(main, both.getNote(ChannelInfo.Voicing.MAIN));
		Assert.assertSame(wet, both.getNote(ChannelInfo.Voicing.WET));

		PatternElement mainOnly = new PatternElement(main, null, 1.0);
		Assert.assertSame(main, mainOnly.getNote(ChannelInfo.Voicing.MAIN));
		Assert.assertNull(mainOnly.getNote(ChannelInfo.Voicing.WET));
		Assert.assertEquals(1, mainOnly.getNotes().size());

		PatternElement wetOnly = new PatternElement(null, wet, 1.0);
		Assert.assertNull(wetOnly.getNote(ChannelInfo.Voicing.MAIN));
		Assert.assertSame(wet, wetOnly.getNote(ChannelInfo.Voicing.WET));

		PatternElement single = new PatternElement(main, 3.0);
		single.setNote(ChannelInfo.Voicing.WET, wet);
		Assert.assertEquals(2, single.getNotes().size());
		Assert.assertSame(wet, single.getNote(ChannelInfo.Voicing.WET));
	}

	/** Repetitions are spaced by the repeat duration starting at the element position. */
	@Test(timeout = 10000)
	public void positionsExpandRepetitions() {
		PatternElement element = new PatternElement(new HashMap<>(), 1.0);
		element.setRepeatCount(4);
		element.setRepeatDuration(0.25);

		Assert.assertEquals(List.of(1.0, 1.25, 1.5, 1.75), element.getPositions());

		element.setRepeatCount(0);
		Assert.assertTrue("zero repetitions produce no positions",
				element.getPositions().isEmpty());
	}

	/**
	 * {@link PatternElement#isPresent} uses a half-open interval and considers every
	 * repetition, not just the first position.
	 */
	@Test(timeout = 10000)
	public void presenceUsesHalfOpenIntervalAcrossRepetitions() {
		PatternElement element = new PatternElement(new HashMap<>(), 1.0);
		element.setRepeatCount(3);
		element.setRepeatDuration(0.5);

		Assert.assertTrue("start boundary is inclusive", element.isPresent(1.0, 1.1));
		Assert.assertFalse("end boundary is exclusive", element.isPresent(0.0, 1.0));
		Assert.assertTrue("last repetition at 2.0 lies in [1.9, 2.1)", element.isPresent(1.9, 2.1));
		Assert.assertFalse("gap between repetitions", element.isPresent(1.1, 1.4));
		Assert.assertFalse("after the last repetition", element.isPresent(2.1, 10.0));

		element.setRepeatCount(1);
		Assert.assertFalse("second repetition no longer exists", element.isPresent(1.4, 1.6));
	}

	/** Each duration strategy computes the note length according to its contract. */
	@Test(timeout = 10000)
	public void durationStrategiesComputeLength() {
		Assert.assertEquals("NONE keeps the original duration", 3.0,
				NoteDurationStrategy.NONE.getLength(TWO_SECONDS_PER_MEASURE,
						0.0, 1.0, 3.0, 0.25), 1e-12);

		Assert.assertEquals("FIXED converts the selection to seconds", 0.5,
				NoteDurationStrategy.FIXED.getLength(TWO_SECONDS_PER_MEASURE,
						0.0, 1.0, 3.0, 0.25), 1e-12);
		Assert.assertEquals("FIXED is clamped to the original duration", 3.0,
				NoteDurationStrategy.FIXED.getLength(TWO_SECONDS_PER_MEASURE,
						0.0, 1.0, 3.0, 4.0), 1e-12);

		Assert.assertEquals("NO_OVERLAP extends to the next position", 1.5,
				NoteDurationStrategy.NO_OVERLAP.getLength(TWO_SECONDS_PER_MEASURE,
						0.5, 1.25, 3.0, 0.25), 1e-12);
		Assert.assertEquals("NO_OVERLAP without a next position keeps the original duration", 3.0,
				NoteDurationStrategy.NO_OVERLAP.getLength(TWO_SECONDS_PER_MEASURE,
						0.5, 0.0, 3.0, 0.25), 1e-12);
		Assert.assertEquals("NO_OVERLAP with a next position before the note keeps the original duration", 3.0,
				NoteDurationStrategy.NO_OVERLAP.getLength(TWO_SECONDS_PER_MEASURE,
						0.5, 0.25, 3.0, 0.25), 1e-12);
		Assert.assertEquals("NO_OVERLAP with a next position at the note keeps the original duration", 3.0,
				NoteDurationStrategy.NO_OVERLAP.getLength(TWO_SECONDS_PER_MEASURE,
						0.5, 0.5, 3.0, 0.25), 1e-12);
	}

	/** {@link PatternElement#getNoteDuration} delegates to its strategy using its own selection. */
	@Test(timeout = 10000)
	public void noteDurationUsesElementSelection() {
		PatternElement element = new PatternElement();
		element.setNoteDurationSelection(0.125);
		Assert.assertEquals(0.125, element.getNoteDurationSelection(), 0.0);

		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		Assert.assertEquals(0.25,
				element.getNoteDuration(TWO_SECONDS_PER_MEASURE, 0.0, 1.0, 10.0), 1e-12);

		element.setDurationStrategy(NoteDurationStrategy.NO_OVERLAP);
		Assert.assertEquals(1.0,
				element.getNoteDuration(TWO_SECONDS_PER_MEASURE, 0.5, 1.0, 10.0), 1e-12);

		element.setDurationStrategy(NoteDurationStrategy.NONE);
		Assert.assertEquals(10.0,
				element.getNoteDuration(TWO_SECONDS_PER_MEASURE, 0.5, 1.0, 10.0), 1e-12);
	}

	/**
	 * The effective duration honours the note's natural length under
	 * {@link NoteDurationStrategy#NONE}, is clamped to it under
	 * {@link NoteDurationStrategy#FIXED}, and follows the voicing's next position
	 * under {@link NoteDurationStrategy#NO_OVERLAP}. The natural length depends on
	 * the target pitch: an octave up plays the same sample in half the time.
	 */
	@Test(timeout = 30000)
	public void effectiveDurationFollowsStrategyAndPitch() {
		NoteAudioProvider provider = NoteAudioProvider.create(
				() -> new PackedCollection(OutputLine.sampleRate), WesternChromatic.C1);
		provider.setTuning(new DefaultKeyboardTuning());

		PatternElement element = new PatternElement(
				new PatternNote(new SimplePatternNote(provider), null), 0.0);
		ElementVoicingDetails root = new ElementVoicingDetails(ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.LEFT, true, WesternChromatic.C1, 0.0, 0.25);
		ElementVoicingDetails octave = new ElementVoicingDetails(ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.LEFT, true, WesternChromatic.C2, 0.0, 0.25);

		double natural = element.getEffectiveDuration(root, null, TWO_SECONDS_PER_MEASURE);
		Assert.assertEquals("one second of audio at its root pitch", 1.0, natural, 1e-6);
		Assert.assertEquals("an octave up halves the duration", natural / 2.0,
				element.getEffectiveDuration(octave, null, TWO_SECONDS_PER_MEASURE), 1e-6);

		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.125);
		Assert.assertEquals(0.25,
				element.getEffectiveDuration(root, null, TWO_SECONDS_PER_MEASURE), 1e-9);
		element.setNoteDurationSelection(5.0);
		Assert.assertEquals("FIXED never exceeds the natural length", natural,
				element.getEffectiveDuration(root, null, TWO_SECONDS_PER_MEASURE), 1e-9);

		element.setDurationStrategy(NoteDurationStrategy.NO_OVERLAP);
		Assert.assertEquals("NO_OVERLAP spans position 0.0 to next position 0.25", 0.5,
				element.getEffectiveDuration(root, null, TWO_SECONDS_PER_MEASURE), 1e-9);
	}

	/** Setters replace the scheduling properties they name. */
	@Test(timeout = 10000)
	public void settersReplaceProperties() {
		PatternElement element = new PatternElement();
		element.setPosition(7.0);
		element.setDirection(PatternDirection.BACKWARD);
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		element.setScalePosition(List.of(0.2, 0.8));

		Assert.assertEquals(7.0, element.getPosition(), 0.0);
		Assert.assertEquals(PatternDirection.BACKWARD, element.getDirection());
		Assert.assertEquals(ScaleTraversalStrategy.SEQUENCE, element.getScaleTraversalStrategy());
		Assert.assertEquals(List.of(0.2, 0.8), element.getScalePositions());
	}
}
