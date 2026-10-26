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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trip and error tests for {@link PatternText}: a full pattern formatted to text and
 * parsed back must reproduce the original model, re-formatting must be stable, the null and
 * empty states must round-trip, hand-written minimal elements must parse with the documented
 * defaults, and malformed input and unrepresentable types must be rejected.
 */
public class PatternTextTest extends TestSuiteBase {

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

	/** Asserts that the parsed elements are distinct instances with equal content, in order. */
	private void assertContentEquals(List<PatternElement> expected, List<PatternElement> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			Assert.assertNotSame(expected.get(i), actual.get(i));
			assertTrue("Element " + i, expected.get(i).contentEquals(actual.get(i)));
		}
	}

	/** A full pattern formats and parses back to elements equal in content, in order. */
	@Test(timeout = 60000)
	public void fullPatternRoundTrips() {
		List<PatternElement> pattern = fullPattern();
		List<PatternElement> parsed = PatternText.parse(PatternText.format(pattern));

		assertContentEquals(pattern, parsed);
		assertEquals(pattern.get(0).getNotes(), parsed.get(0).getNotes());
		assertEquals(pattern.get(0).getAutomationParameters().getShape(),
				parsed.get(0).getAutomationParameters().getShape());
		assertNull(parsed.get(2).getAutomationParameters());
	}

	/**
	 * Re-formatting a parsed pattern reproduces the original text exactly, which also covers
	 * state that content equality does not compare (a note's aggregation choice and the
	 * envelope selection functions behind each filter).
	 */
	@Test(timeout = 60000)
	public void reformattingIsStable() {
		List<PatternElement> pattern = fullPattern();
		String text = PatternText.format(pattern);
		String reformatted = PatternText.format(PatternText.parse(text));
		assertEquals(text, reformatted);
	}

	/** A default element, including its default strategies, round-trips unchanged. */
	@Test(timeout = 60000)
	public void defaultElementRoundTrips() {
		PatternElement element = new PatternElement(new PatternNote(0.3), 0.0);
		PatternElement parsed = PatternElement.fromText(element.toText());
		assertTrue(element.contentEquals(parsed));
	}

	/** Null strategies, a null direction and a voicing with no note round-trip as such. */
	@Test(timeout = 60000)
	public void nullStatesRoundTrip() {
		PatternElement element = new PatternElement(new PatternNote(0.3), 0.75);
		element.setDurationStrategy(null);
		element.setScaleTraversalStrategy(null);
		element.setDirection(null);
		element.setNote(ChannelInfo.Voicing.WET, null);

		PatternElement parsed = PatternElement.fromText(element.toText());
		assertNull(parsed.getDurationStrategy());
		assertNull(parsed.getScaleTraversalStrategy());
		assertNull(parsed.getDirection());
		assertTrue(parsed.getNotes().containsKey(ChannelInfo.Voicing.WET));
		assertNull(parsed.getNotes().get(ChannelInfo.Voicing.WET));
		assertTrue(element.contentEquals(parsed));
	}

	/** A note held under a null voicing key round-trips as a null-keyed entry. */
	@Test(timeout = 60000)
	public void nullVoicingKeyRoundTrips() {
		PatternElement element = new PatternElement();
		element.setNote(null, new PatternNote(0.3));

		PatternElement parsed = PatternElement.fromText(element.toText());
		assertTrue(parsed.getNotes().containsKey(null));
		assertTrue(element.contentEquals(parsed));
	}

	/**
	 * A hand-written minimal element — only a position and a {@code choices} note — parses
	 * with the documented constructor defaults for every omitted field.
	 */
	@Test(timeout = 60000)
	public void minimalElementUsesDefaults() {
		PatternElement parsed = PatternElement.fromText(
				"element {\n  position 1.25\n  note MAIN {\n    choices [0.3]\n  }\n}\n");

		assertEquals(1.25, parsed.getPosition(), 0.0);
		assertEquals(NoteDurationStrategy.NONE, parsed.getDurationStrategy());
		assertEquals(ScaleTraversalStrategy.CHORD, parsed.getScaleTraversalStrategy());
		assertEquals(List.of(0.0), parsed.getScalePositions());
		assertEquals(PatternDirection.FORWARD, parsed.getDirection());
		assertEquals(1, parsed.getRepeatCount());
		assertEquals(1.0, parsed.getRepeatDuration(), 0.0);
		assertNull(parsed.getAutomationParameters());

		PatternNote note = parsed.getNote(ChannelInfo.Voicing.MAIN);
		assertEquals(1, note.getLayers().size());
		assertTrue(note.getLayers().get(0) instanceof PatternNoteAudioChoice);
	}

	/** Comments and arbitrary whitespace between tokens are ignored by the parser. */
	@Test(timeout = 60000)
	public void commentsAndWhitespaceAreIgnored() {
		PatternElement parsed = PatternElement.fromText(
				"# leading comment\nelement {\n"
						+ "  position 2.0   # trailing comment\n"
						+ "  note MAIN { choices [ 0.5 , 0.6 ] }\n"
						+ "}\n");
		assertEquals(2.0, parsed.getPosition(), 0.0);
		assertEquals(2, parsed.getNote(ChannelInfo.Voicing.MAIN).getLayers().size());
	}

	/** An unknown keyword is rejected with a located {@link PatternText.ParseException}. */
	@Test(timeout = 60000)
	public void unknownKeywordIsRejected() {
		PatternText.ParseException e = assertParseFails("element {\n  tempo 120\n}\n");
		assertEquals(2, e.getLine());
		assertTrue(e.getMessage().contains("tempo"));
	}

	/** A value that is not a number where one is required is rejected. */
	@Test(timeout = 60000)
	public void badNumberIsRejected() {
		PatternText.ParseException e = assertParseFails("element {\n  position here\n}\n");
		assertTrue(e.getMessage().contains("number"));
	}

	/** An unbalanced brace is reported as unexpected end of input. */
	@Test(timeout = 60000)
	public void unbalancedBraceIsRejected() {
		assertParseFails("element {\n  position 1.0\n");
	}

	/** A field that appears twice in the same block is rejected as a duplicate. */
	@Test(timeout = 60000)
	public void duplicateFieldIsRejected() {
		PatternText.ParseException e =
				assertParseFails("element {\n  position 1.0\n  position 2.0\n}\n");
		assertTrue(e.getMessage().contains("duplicate"));
	}

	/** Input after the closing brace of a single element is rejected as trailing input. */
	@Test(timeout = 60000)
	public void trailingInputIsRejected() {
		assertParseFails("element {\n  position 1.0\n}\nelement { }\n");
	}

	/** The same voicing declared twice in one element is rejected rather than overwritten. */
	@Test(timeout = 60000)
	public void duplicateVoicingIsRejected() {
		PatternText.ParseException e = assertParseFails(
				"element {\n  note MAIN { choices [0.3] }\n  note MAIN none\n}\n");
		assertEquals(3, e.getLine());
		assertTrue(e.getMessage().contains("duplicate note voicing 'MAIN'"));
	}

	/** A note that mixes the layer form with the delegate form is rejected as ambiguous. */
	@Test(timeout = 60000)
	public void layersWithDelegateAreRejected() {
		PatternText.ParseException e = assertParseFails(
				"element {\n  note MAIN {\n    choices [0.3]\n    delegate { choice 0.5 }\n  }\n}\n");
		assertTrue(e.getMessage().contains("both layers and a delegate"));
	}

	/** A filter block with no envelope definition cannot be rebuilt and is rejected. */
	@Test(timeout = 60000)
	public void filterWithoutEnvelopeIsRejected() {
		PatternText.ParseException e = assertParseFails(
				"element {\n  note MAIN {\n    delegate { choice 0.5 }\n"
						+ "    filter { params none voicing MAIN }\n  }\n}\n");
		assertTrue(e.getMessage().contains("filter has no envelope"));
	}

	/** Automation whose value count disagrees with its declared shape is rejected. */
	@Test(timeout = 60000)
	public void automationSizeMismatchIsRejected() {
		PatternText.ParseException e =
				assertParseFails("element {\n  automation [2, 3] [0.1, 0.2]\n}\n");
		assertTrue(e.getMessage().contains("automation shape 6 does not match 2 values"));
	}

	/** An enum field naming a constant that does not exist is rejected with the enum's name. */
	@Test(timeout = 60000)
	public void unknownEnumValueIsRejected() {
		PatternText.ParseException e = assertParseFails("element {\n  direction SIDEWAYS\n}\n");
		assertEquals(2, e.getLine());
		assertTrue(e.getMessage().contains("'SIDEWAYS' is not a value of PatternDirection"));
	}

	/** A fractional repeat count is rejected where an integer is required. */
	@Test(timeout = 60000)
	public void fractionalRepeatCountIsRejected() {
		PatternText.ParseException e =
				assertParseFails("element {\n  repeat 1.5 every 1.0\n}\n");
		assertTrue(e.getMessage().contains("expected an integer but found '1.5'"));
	}

	/** A filter the format cannot represent is rejected on format rather than silently dropped. */
	@Test(timeout = 60000)
	public void unsupportedFilterIsRejected() {
		PatternNote note = new PatternNote(new PatternNoteAudioChoice(0.5),
				(audio, duration, automationLevel) -> audio);
		PatternElement element = new PatternElement(note, 0.0);

		try {
			element.toText();
			Assert.fail("Formatting a filter with no text representation should fail");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("note audio filter"));
		}
	}

	/** The exception methods report the same location that the message names. */
	@Test(timeout = 60000)
	public void parseExceptionCarriesLocation() {
		PatternText.ParseException e = new PatternText.ParseException("boom", 3, 7);
		assertEquals(3, e.getLine());
		assertEquals(7, e.getColumn());
		assertTrue(e.getMessage().contains("line"));
		assertTrue(e.getMessage().contains("column"));
	}

	/** A single element formatted, parsed and re-formatted reproduces its own text. */
	@Test(timeout = 60000)
	public void singleElementTextIsStable() {
		PatternElement element = fullPattern().get(0);
		String single = element.toText();
		assertEquals(single, PatternElement.fromText(single).toText());
	}

	/** Asserts that parsing the given text fails with a {@link PatternText.ParseException}. */
	private PatternText.ParseException assertParseFails(String text) {
		try {
			PatternText.parseElement(text);
			Assert.fail("Expected a ParseException for: " + text);
			return null;
		} catch (PatternText.ParseException expected) {
			return expected;
		}
	}
}
