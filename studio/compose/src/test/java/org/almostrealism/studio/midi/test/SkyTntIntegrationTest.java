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

import org.almostrealism.studio.midi.SkyTntMidi;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.studio.midi.SkyTntTokenizerV2;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Milestones 6 and 7 integration tests for the SkyTNT midi-model.
 *
 * <p>All tests in this class are guarded by {@link Assume} conditions so that
 * they are silently skipped in CI environments that lack the real model weights.
 * The weights directory is checked for existence at runtime; if it is absent the
 * test is skipped rather than failing.</p>
 *
 * <h2>Milestone 6 — Numerical Validation</h2>
 * <p>Loads real weights and generates from BOS, verifying the output is a
 * plausible MIDI token sequence (all step-0 tokens are valid event types).</p>
 *
 * <h2>Milestone 7 — End-to-End via Tokenizer</h2>
 * <p>Tokenizes a hand-crafted prompt, runs generation, detokenizes the result,
 * and verifies the generated events have valid parameter ranges.</p>
 *
 * @see SkyTntMidi
 */
public class SkyTntIntegrationTest extends TestSuiteBase {

	/**
	 * Expected path to the real model weights.
	 *
	 * <p>In the CI environment, models are mounted under {@code /models}. On macOS
	 * developer machines the conventional path is under {@code /Users/Shared/models}.
	 * The path is selected at runtime based on the operating system.</p>
	 */
	private static final String WEIGHTS_DIR = resolveWeightsDir();

	/**
	 * Returns the model weights directory appropriate for the current OS.
	 *
	 * <p>On Linux (CI), weights are mounted at {@code /models/skytnt-weights-protobuf}.
	 * On macOS (local development), the conventional path {@code /Users/Shared/models/skytnt-weights-protobuf}
	 * is used.</p>
	 */
	private static String resolveWeightsDir() {
		boolean isLinux = System.getProperty("os.name", "").toLowerCase().contains("linux");
		return isLinux ? "/models/skytnt-weights-protobuf"
				: "/Users/Shared/models/skytnt-weights-protobuf";
	}

	/** Maximum events to generate in each test (keeps runtime bounded). */
	private static final int MAX_NEW_EVENTS = 20;

	/**
	 * Milestone 6: load real weights, generate unconditionally from BOS,
	 * and verify the token sequence is well-formed.
	 */
	@Test
	public void testRealWeightsGenerationFromBos() throws IOException {
		Assume.assumeTrue("Skipping: real weights not present at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());

		SkyTntMidi model = new SkyTntMidi(WEIGHTS_DIR);

		// BOS prompt
		int[][] prompt = new int[1][model.getConfig().maxTokenSeq];
		prompt[0][0] = model.getConfig().bosId;

		int[][] output = model.generate(prompt, MAX_NEW_EVENTS,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K);

		Assert.assertNotNull("Output should not be null", output);
		Assert.assertTrue("Should have generated at least one event", output.length > 1);

		// Verify each generated row has a valid step-0 token
		for (int i = 1; i < output.length; i++) {
			int step0 = output[i][0];
			boolean isValidEvent = (step0 >= SkyTntTokenizerV2.EVENT_NOTE
					&& step0 <= SkyTntTokenizerV2.EVENT_KEY_SIGNATURE);
			boolean isEos = (step0 == model.getConfig().eosId);
			Assert.assertTrue("Row " + i + " step-0 token " + step0 +
					" should be valid event type or EOS", isValidEvent || isEos);
		}
	}

	/**
	 * Milestone 7: tokenize a small prompt, generate complementary events,
	 * and detokenize the result.  Verifies that detokenized events have
	 * parameters within valid ranges.
	 */
	@Test
	public void testRealWeightsGenerationFromPrompt() throws IOException {
		Assume.assumeTrue("Skipping: real weights not present at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());

		SkyTntMidi model = new SkyTntMidi(WEIGHTS_DIR);

		// Build a minimal prompt: BOS + one C4 note
		List<MidiNoteEvent> promptEvents = new ArrayList<>();
		promptEvents.add(MidiNoteEvent.note(0, 0, 0, 60, 80, 16));  // tick=0, track=0, ch=0, pitch=60, vel=80, dur=16

		List<MidiNoteEvent> generated = model.generateFromEvents(
				promptEvents, MAX_NEW_EVENTS,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K,
				SkyTntMidi.DEFAULT_TICKS_PER_BEAT);

		Assert.assertNotNull("Generated events should not be null", generated);

		// Verify parameter ranges for any generated note events
		for (MidiNoteEvent event : generated) {
			if (event.getEventType() == MidiNoteEvent.EventType.NOTE) {
				Assert.assertTrue("Pitch should be in [0, 127]",
						event.getPitch() >= 0 && event.getPitch() <= 127);
				Assert.assertTrue("Velocity should be in [0, 127]",
						event.getVelocity() >= 0 && event.getVelocity() <= 127);
			}
		}
	}

}
