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

package org.almostrealism.studio.midi.test;

import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.studio.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for MIDI tokenization round-trip, compound token construction,
 * and {@link MoonbeamConfig} validation.
 */
public class MidiTokenizerTest extends TestSuiteBase {

	/**
	 * Verify that tokenizing note events and detokenizing back
	 * preserves pitch, duration, velocity, and instrument.
	 * Onset times are reconstructed from deltas and should match
	 * the originals.
	 */
	@Test(timeout = 60000)
	public void testRoundTrip() {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 50, 80, 0));     // C4 at t=0
		events.add(new MidiNoteEvent(64, 100, 50, 90, 0));   // E4 at t=100
		events.add(new MidiNoteEvent(67, 200, 100, 100, 0)); // G4 at t=200

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> tokens = tokenizer.tokenize(events);

		assertEquals("Token count should be events + SOS + EOS",
				events.size() + 2, tokens.size());
		assertTrue("First token should be SOS", tokens.get(0).isSOS());
		assertTrue("Last token should be EOS", tokens.get(tokens.size() - 1).isEOS());

		List<MidiNoteEvent> reconstructed = tokenizer.detokenize(tokens);
		assertEquals("Reconstructed event count", events.size(), reconstructed.size());

		for (int i = 0; i < events.size(); i++) {
			MidiNoteEvent original = events.get(i);
			MidiNoteEvent recon = reconstructed.get(i);
			assertEquals("Pitch mismatch at " + i, original.getPitch(), recon.getPitch());
			assertEquals("Onset mismatch at " + i, original.getOnset(), recon.getOnset());
			assertEquals("Duration mismatch at " + i,
					original.getDuration(), recon.getDuration());
			assertEquals("Velocity mismatch at " + i,
					original.getVelocity(), recon.getVelocity());
			assertEquals("Instrument mismatch at " + i,
					original.getInstrument(), recon.getInstrument());
		}
	}

	/**
	 * Verify that compound tokens correctly decompose pitch into
	 * octave and pitch class, and that onset deltas are computed.
	 */
	@Test(timeout = 60000)
	public void testCompoundTokenAttributes() {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 50, 80, 0));     // C4: octave=5, pc=0
		events.add(new MidiNoteEvent(73, 150, 30, 70, 5));   // C#5: octave=6, pc=1

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> tokens = tokenizer.tokenize(events);

		MidiCompoundToken first = tokens.get(1);
		assertEquals("First onset delta", 0, first.getOnset());
		assertEquals("First duration", 50, first.getDuration());
		assertEquals("First octave (60/12=5)", 5, first.getOctave());
		assertEquals("First pitch class (60%12=0)", 0, first.getPitchClass());
		assertEquals("First instrument", 0, first.getInstrument());
		assertEquals("First velocity", 80, first.getVelocity());

		MidiCompoundToken second = tokens.get(2);
		assertEquals("Second onset delta", 150, second.getOnset());
		assertEquals("Second octave (73/12=6)", 6, second.getOctave());
		assertEquals("Second pitch class (73%12=1)", 1, second.getPitchClass());
		assertEquals("Second instrument", 5, second.getInstrument());
	}

	/**
	 * Verify special token factory methods produce correct sentinel values.
	 */
	@Test(timeout = 60000)
	public void testSpecialTokens() {
		MidiCompoundToken sos = MidiCompoundToken.sos();
		assertTrue("SOS should be special", sos.isSpecial());
		assertTrue("SOS isSOS", sos.isSOS());
		assertFalse("SOS is not EOS", sos.isEOS());

		MidiCompoundToken eos = MidiCompoundToken.eos();
		assertTrue("EOS should be special", eos.isSpecial());
		assertTrue("EOS isEOS", eos.isEOS());

		MidiCompoundToken pad = MidiCompoundToken.pad();
		assertTrue("PAD should be special", pad.isSpecial());
		assertTrue("PAD isPAD", pad.isPAD());
	}

	/**
	 * Verify toModelInput produces correct shape and values.
	 */
	@Test(timeout = 60000)
	public void testToModelInput() {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 50, 80, 0));

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> tokens = tokenizer.tokenize(events);
		int[][] input = tokenizer.toModelInput(tokens);

		assertEquals("Row count", tokens.size(), input.length);
		assertEquals("Column count", MidiCompoundToken.ATTRIBUTE_COUNT, input[0].length);
	}

	/**
	 * Verify that MidiCompoundToken.fromArray round-trips correctly.
	 */
	@Test(timeout = 60000)
	public void testCompoundTokenArrayRoundTrip() {
		MidiCompoundToken token = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		int[] array = token.toArray();
		MidiCompoundToken rebuilt = MidiCompoundToken.fromArray(array);
		Assert.assertEquals("Array round-trip should preserve token", token, rebuilt);
	}

	/**
	 * Verify MoonbeamConfig default configuration is valid.
	 */
	@Test(timeout = 60000)
	public void testDefaultConfigValid() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		config.validate();

		assertEquals("hiddenSize", 1920, config.hiddenSize);
		assertEquals("numLayers", 15, config.numLayers);
		assertEquals("numHeads", 12, config.numHeads);
		assertEquals("numKvHeads", 6, config.numKvHeads);
		assertEquals("headDim", 160, config.headDim);
		assertEquals("embeddingDim", 320, config.embeddingDim);
		assertEquals("headsPerKVGroup", 2, config.getHeadsPerKVGroup());
	}

	/**
	 * Verify MoonbeamConfig test configuration is valid.
	 */
	@Test(timeout = 60000)
	public void testTestConfigValid() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		config.validate();
		assertEquals("test embeddingDim", 8, config.embeddingDim);
	}

	/**
	 * Verify that tokenizing an empty event list produces only SOS and EOS.
	 */
	@Test(timeout = 60000)
	public void testEmptySequence() {
		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> tokens = tokenizer.tokenize(new ArrayList<>());
		assertEquals("Empty sequence should have SOS + EOS", 2, tokens.size());
		assertTrue("First is SOS", tokens.get(0).isSOS());
		assertTrue("Second is EOS", tokens.get(1).isEOS());
	}

	/**
	 * Verify that large onset and duration values are clamped.
	 */
	@Test(timeout = 60000)
	public void testValueClamping() {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 999999, 80, 0));
		events.add(new MidiNoteEvent(64, 999999, 50, 90, 0));

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> tokens = tokenizer.tokenize(events);

		MidiCompoundToken first = tokens.get(1);
		assertEquals("Duration should be clamped",
				MidiTokenizer.MAX_TIME_VALUE, first.getDuration());

		MidiCompoundToken second = tokens.get(2);
		assertEquals("Onset delta should be clamped",
				MidiTokenizer.MAX_TIME_VALUE, second.getOnset());
	}

	/**
	 * Verify that MidiCompoundToken.fromArray rejects arrays
	 * with the wrong number of elements.
	 */
	@Test(timeout = 60000, expected = IllegalArgumentException.class)
	public void testFromArrayInvalidLength() {
		MidiCompoundToken.fromArray(new int[]{1, 2, 3});
	}

	/**
	 * Verify that MidiNoteEvent sorts by onset first, then by pitch.
	 */
	@Test(timeout = 60000)
	public void testNoteEventOrdering() {
		MidiNoteEvent early = new MidiNoteEvent(72, 0, 50, 80, 0);
		MidiNoteEvent late = new MidiNoteEvent(60, 100, 50, 80, 0);
		MidiNoteEvent sameOnsetLowPitch = new MidiNoteEvent(48, 0, 50, 80, 0);

		assertTrue("Earlier onset should sort before later onset",
				early.compareTo(late) < 0);
		assertTrue("Same onset: lower pitch should sort first",
				sameOnsetLowPitch.compareTo(early) < 0);
		assertEquals("Same event should compare equal", 0,
				early.compareTo(new MidiNoteEvent(72, 0, 50, 80, 0)));
	}

	/**
	 * Verify MidiNoteEvent equals and hashCode contract.
	 */
	@Test(timeout = 60000)
	public void testNoteEventEquality() {
		MidiNoteEvent a = new MidiNoteEvent(60, 100, 50, 80, 0);
		MidiNoteEvent b = new MidiNoteEvent(60, 100, 50, 80, 0);
		MidiNoteEvent c = new MidiNoteEvent(61, 100, 50, 80, 0);

		Assert.assertEquals("Equal events", a, b);
		assertEquals("Equal events same hashCode", a.hashCode(), b.hashCode());
		assertFalse("Different pitch should not be equal", a.equals(c));
		assertFalse("Should not equal null", a.equals(null));
		assertFalse("Should not equal other type", a.equals("not a note"));
	}

	/**
	 * Verify MidiNoteEvent octave and pitch class derivation.
	 */
	@Test(timeout = 60000)
	public void testNoteEventOctaveAndPitchClass() {
		MidiNoteEvent middleC = new MidiNoteEvent(60, 0, 50, 80, 0);
		assertEquals("C4 octave", 5, middleC.getOctave());
		assertEquals("C4 pitch class", 0, middleC.getPitchClass());

		MidiNoteEvent a4 = new MidiNoteEvent(69, 0, 50, 80, 0);
		assertEquals("A4 octave", 5, a4.getOctave());
		assertEquals("A4 pitch class", 9, a4.getPitchClass());

		MidiNoteEvent highest = new MidiNoteEvent(127, 0, 50, 80, 0);
		assertEquals("G9 octave", 10, highest.getOctave());
		assertEquals("G9 pitch class", 7, highest.getPitchClass());
	}

	/**
	 * Verify MoonbeamConfig.validate rejects invalid head configuration.
	 */
	@Test(timeout = 60000, expected = IllegalStateException.class)
	public void testConfigInvalidHeads() {
		new MoonbeamConfig(
				48, 144, 2, 7, 6, 8,
				32, 2, 8487, 128, 1e-5,
				new double[]{199999, 1031, 19, 20, 199999, 131},
				new int[]{1, 1, 1, 1, 1, 1},
				new int[]{4099, 4099, 13, 14, 131, 130},
				new double[]{199999, 1031, 19, 20, 199999, 131},
				2
		).validate();
	}

	/**
	 * Verify MoonbeamConfig.validate rejects mismatched headsPerGroup sum.
	 */
	@Test(timeout = 60000, expected = IllegalStateException.class)
	public void testConfigInvalidHeadsPerGroup() {
		new MoonbeamConfig(
				48, 144, 2, 6, 6, 8,
				32, 2, 8487, 128, 1e-5,
				new double[]{199999, 1031, 19, 20, 199999, 131},
				new int[]{2, 2, 2, 2, 2, 2},
				new int[]{4099, 4099, 13, 14, 131, 130},
				new double[]{199999, 1031, 19, 20, 199999, 131},
				2
		).validate();
	}

	/**
	 * Verify MidiCompoundToken equality and hashCode contract.
	 */
	@Test(timeout = 60000)
	public void testCompoundTokenEquality() {
		MidiCompoundToken a = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		MidiCompoundToken b = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		MidiCompoundToken c = new MidiCompoundToken(200, 50, 5, 0, 0, 80);

		Assert.assertEquals("Equal tokens", a, b);
		assertEquals("Equal tokens same hashCode", a.hashCode(), b.hashCode());
		assertFalse("Different tokens", a.equals(c));
	}
}
