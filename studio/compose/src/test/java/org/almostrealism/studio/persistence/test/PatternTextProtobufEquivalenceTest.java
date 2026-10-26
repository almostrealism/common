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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.pattern.NoteDurationStrategy;
import org.almostrealism.music.pattern.PatternDirection;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternNoteFactory;
import org.almostrealism.music.pattern.PatternText;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.studio.persistence.PatternElementPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves that the text format ({@link PatternText}) and the protobuf format
 * ({@link PatternElementPersistence}) describe the same pattern model: for the same source
 * pattern the two codecs produce content-equal models, and converting a pattern from one
 * format to the other and back reproduces the original exactly. This is the guard that fails
 * the build if either codec gains a type or field the other lacks.
 */
public class PatternTextProtobufEquivalenceTest extends TestSuiteBase {

	/**
	 * Builds a melodic element the way {@code PatternNoteFactory} does: a three-layer note
	 * whose layers are shaped by layer envelopes, wrapped in filter and volume envelopes for
	 * each voicing, with several strategies, repeats and 2&times;3 automation set.
	 */
	private PatternElement melodicElement(PatternNoteFactory noteFactory,
										  ParameterizedFilterEnvelope filterEnvelope,
										  ParameterizedVolumeEnvelope volumeEnvelope,
										  PackedCollection automation) {
		ParameterSet params = new ParameterSet(0.31, -0.72, 0.18);
		PatternNote choice = noteFactory.apply(params, null, true, 0.12, 0.47, 0.83);

		PatternNote main = volumeEnvelope.apply(params, ChannelInfo.Voicing.MAIN,
				filterEnvelope.apply(params, ChannelInfo.Voicing.MAIN, choice));
		PatternNote wet = volumeEnvelope.apply(params, ChannelInfo.Voicing.WET,
				filterEnvelope.apply(params, ChannelInfo.Voicing.WET, choice));

		PatternElement element = new PatternElement(main, wet, 2.25);
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.375);
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		element.setScalePosition(List.of(0.0, 0.25, 0.625));
		element.setDirection(PatternDirection.BACKWARD);
		element.setRepeatCount(4);
		element.setRepeatDuration(0.5);
		element.setAutomationParameters(automation);
		return element;
	}

	/** Builds a full pattern with several elements, notes, strategies and automation data. */
	private List<PatternElement> fullPattern() {
		PatternNoteFactory noteFactory = new PatternNoteFactory();
		ParameterizedFilterEnvelope filterEnvelope =
				ParameterizedFilterEnvelope.random(ParameterizedFilterEnvelope.Mode.STANDARD_NOTE);
		ParameterizedVolumeEnvelope volumeEnvelope =
				ParameterizedVolumeEnvelope.random(ParameterizedVolumeEnvelope.Mode.NOTE_LAYER);

		PatternElement melodic = melodicElement(noteFactory, filterEnvelope, volumeEnvelope,
				pack(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).reshape(shape(2, 3)));

		PatternNote layered = new PatternNote(0.2, 0.65);
		layered.setAggregationChoice(0.4);
		PatternElement percussive = new PatternElement(layered, 1.5);
		percussive.setDurationStrategy(NoteDurationStrategy.NO_OVERLAP);
		percussive.setAutomationParameters(pack(0.9, -0.25, 1.0 / 3.0));

		PatternElement unblended = new PatternElement(
				noteFactory.apply(new ParameterSet(0.5, 0.5, 0.5), ChannelInfo.Voicing.MAIN,
						false, 0.05, 0.5, 0.95), 3.0);
		unblended.setRepeatCount(2);
		unblended.setRepeatDuration(0.25);

		return List.of(melodic, percussive, unblended);
	}

	/** Asserts that two patterns are element-wise equal in content, in order. */
	private void assertContentEquals(List<PatternElement> expected, List<PatternElement> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			assertTrue("Element " + i, expected.get(i).contentEquals(actual.get(i)));
		}
	}

	/**
	 * For the same source pattern, the text codec and the protobuf codec produce
	 * content-equal models, and re-encoding either model to protobuf (the strictest available
	 * equality, covering every field of both formats) yields equal {@link Audio.PatternData}.
	 */
	@Test(timeout = 90000)
	public void textAndProtobufAgreeForTheSamePattern() {
		List<PatternElement> source = fullPattern();

		List<PatternElement> viaText = PatternText.parse(PatternText.format(source));
		List<PatternElement> viaProtobuf = PatternElementPersistence.fromPatternData(
				PatternElementPersistence.toPatternData(source));

		assertContentEquals(viaProtobuf, viaText);

		Audio.PatternData textEncoded = PatternElementPersistence.toPatternData(viaText);
		Audio.PatternData protobufEncoded = PatternElementPersistence.toPatternData(viaProtobuf);
		assertEquals(protobufEncoded, textEncoded);
	}

	/** protobuf &rarr; model &rarr; text &rarr; model &rarr; protobuf reproduces the original message. */
	@Test(timeout = 90000)
	public void protobufThroughTextReproducesProtobuf() {
		Audio.PatternData original = PatternElementPersistence.toPatternData(fullPattern());

		List<PatternElement> fromProtobuf = PatternElementPersistence.fromPatternData(original);
		List<PatternElement> throughText = PatternText.parse(PatternText.format(fromProtobuf));
		Audio.PatternData reencoded = PatternElementPersistence.toPatternData(throughText);

		assertEquals(original, reencoded);
	}

	/** text &rarr; model &rarr; protobuf &rarr; model &rarr; text reproduces the original text. */
	@Test(timeout = 90000)
	public void textThroughProtobufReproducesText() {
		String original = PatternText.format(fullPattern());

		List<PatternElement> fromText = PatternText.parse(original);
		List<PatternElement> throughProtobuf = PatternElementPersistence.fromPatternData(
				PatternElementPersistence.toPatternData(fromText));
		String reformatted = PatternText.format(throughProtobuf);

		assertEquals(original, reformatted);
	}

	/** A pattern with the null and empty states both formats carry agrees across the two codecs. */
	@Test(timeout = 90000)
	public void nullAndEmptyStatesAgree() {
		PatternElement nulls = new PatternElement(new PatternNote(0.3), 0.5);
		nulls.setDurationStrategy(null);
		nulls.setScaleTraversalStrategy(null);
		nulls.setDirection(null);
		nulls.setNote(ChannelInfo.Voicing.WET, null);

		PatternElement nullVoicing = new PatternElement();
		nullVoicing.setNote(null, new PatternNote(0.7));

		List<PatternElement> source = List.of(nulls, nullVoicing);

		Audio.PatternData viaText = PatternElementPersistence.toPatternData(
				PatternText.parse(PatternText.format(source)));
		Audio.PatternData viaProtobuf = PatternElementPersistence.toPatternData(
				PatternElementPersistence.fromPatternData(
						PatternElementPersistence.toPatternData(source)));
		Assert.assertEquals(viaProtobuf, viaText);
	}
}
