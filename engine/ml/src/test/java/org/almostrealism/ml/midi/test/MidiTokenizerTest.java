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

package org.almostrealism.ml.midi.test;

import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MidiNoteEvent;
import org.almostrealism.ml.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestSuiteBase;
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
	@Test
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
	@Test
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
	@Test
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
	@Test
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
	@Test
	public void testCompoundTokenArrayRoundTrip() {
		MidiCompoundToken token = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		int[] array = token.toArray();
		MidiCompoundToken rebuilt = MidiCompoundToken.fromArray(array);
		org.junit.Assert.assertEquals("Array round-trip should preserve token", token, rebuilt);
	}

	/**
	 * Verify MoonbeamConfig default configuration is valid.
	 */
	@Test
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
	@Test
	public void testTestConfigValid() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		config.validate();
		assertEquals("test embeddingDim", 8, config.embeddingDim);
	}

	/**
	 * Verify that tokenizing an empty event list produces only SOS and EOS.
	 */
	@Test
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
	@Test
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
}
