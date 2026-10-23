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

import com.google.protobuf.InvalidProtocolBufferException;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.music.pattern.NoteDurationStrategy;
import org.almostrealism.music.pattern.PatternDirection;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternNoteFactory;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.studio.persistence.PatternElementPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

/**
 * Round-trip tests for {@link PatternElementPersistence}: a full pattern encoded to the
 * {@link Audio.PatternData} protobuf, serialized to bytes, parsed and decoded must equal
 * the original.
 */
public class PatternElementPersistenceTest extends TestSuiteBase {

	/**
	 * Builds a melodic element the way {@code PatternElementFactory} does: a three-layer
	 * note whose layers are shaped by layer envelopes, wrapped in filter and volume
	 * envelopes for each voicing.
	 */
	private static PatternElement melodicElement(PatternNoteFactory noteFactory,
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

	/** Encodes to bytes, parses the bytes and decodes the result. */
	private static List<PatternElement> roundTrip(List<PatternElement> pattern)
			throws InvalidProtocolBufferException {
		byte[] bytes = PatternElementPersistence.toPatternData(pattern).toByteArray();
		return PatternElementPersistence.fromPatternData(Audio.PatternData.parseFrom(bytes));
	}

	/** Asserts that the decoded elements are distinct instances with equal content, in order. */
	private void assertContentEquals(List<PatternElement> expected, List<PatternElement> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			assertNotSame(expected.get(i), actual.get(i));
			Assert.assertTrue("Element " + i, expected.get(i).contentEquals(actual.get(i)));
		}
	}

	/** A full pattern survives serialization and deserialization as an equal object. */
	@Test(timeout = 60000)
	public void fullPatternRoundTripsToEqualElements() throws InvalidProtocolBufferException {
		List<PatternElement> pattern = fullPattern();
		List<PatternElement> decoded = roundTrip(pattern);

		assertContentEquals(pattern, decoded);
		assertEquals(pattern.get(0).getNotes(), decoded.get(0).getNotes());
		assertEquals(pattern.get(0).getAutomationParameters().getShape(),
				decoded.get(0).getAutomationParameters().getShape());
		assertNull(decoded.get(2).getAutomationParameters());
	}

	/**
	 * Re-encoding a decoded pattern reproduces the original encoding exactly, which also
	 * covers state that content equality does not compare (such as a note's aggregation
	 * choice and the envelope selection functions behind each filter).
	 */
	@Test(timeout = 60000)
	public void decodedPatternReencodesIdentically() throws InvalidProtocolBufferException {
		List<PatternElement> pattern = fullPattern();
		Audio.PatternData original = PatternElementPersistence.toPatternData(pattern);
		Audio.PatternData reencoded = PatternElementPersistence.toPatternData(roundTrip(pattern));
		assertEquals(original, reencoded);
	}

	/** A default element, including its default strategies, round-trips unchanged. */
	@Test(timeout = 60000)
	public void defaultElementRoundTrips() throws InvalidProtocolBufferException {
		PatternElement element = new PatternElement(new PatternNote(0.3), 0.0);
		assertContentEquals(List.of(element), roundTrip(List.of(element)));
	}

	/** Unset strategies and voicings are restored as null rather than as a default. */
	@Test(timeout = 60000)
	public void nullStrategiesRoundTripAsNull() throws InvalidProtocolBufferException {
		PatternElement element = new PatternElement(new PatternNote(0.3), 0.75);
		element.setDurationStrategy(null);
		element.setScaleTraversalStrategy(null);
		element.setDirection(null);

		PatternElement decoded = roundTrip(List.of(element)).get(0);
		assertNull(decoded.getDurationStrategy());
		assertNull(decoded.getScaleTraversalStrategy());
		assertNull(decoded.getDirection());
		Assert.assertTrue(element.contentEquals(decoded));
	}

	/**
	 * A difference in automation data alone makes two elements unequal in content, while
	 * {@link Object#equals} keeps comparing elements by instance for the render caches.
	 */
	@Test(timeout = 60000)
	public void automationDifferenceIsDetected() throws InvalidProtocolBufferException {
		PatternElement element = new PatternElement(new PatternNote(0.3), 1.0);
		element.setAutomationParameters(pack(0.1, 0.2, 0.3));

		PatternElement decoded = roundTrip(List.of(element)).get(0);
		Assert.assertTrue(element.contentEquals(decoded));
		assertNotEquals(element, decoded);

		decoded.setAutomationParameters(pack(0.1, 0.2, 0.30001));
		Assert.assertFalse(element.contentEquals(decoded));
	}

	/** A filter with no protobuf representation is rejected rather than silently dropped. */
	@Test(timeout = 60000)
	public void unsupportedFilterIsRejected() {
		PatternNote note = new PatternNote(new PatternNoteAudioChoice(0.5),
				(audio, duration, automationLevel) -> audio);
		PatternElement element = new PatternElement(note, 0.0);

		try {
			PatternElementPersistence.toElementData(element);
			Assert.fail("Encoding a filter with no protobuf representation should fail");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("note audio filter"));
		}
	}
}
