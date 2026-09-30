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

package org.almostrealism.studio.ml;

import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.StableAudio3;
import org.almostrealism.ml.tokenization.SentencePieceBPETokenizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a clip generated from prompt text to a playable WAV file.
 *
 * <p>Every other check on generation is statistical — the samples are finite, within the clamp, of
 * the right length, not silent — and none of that distinguishes music from well-behaved noise. This
 * produces a file a person can open, which is the only thing that can. What it asserts mechanically
 * is the part that can be asserted: that the samples reach the file intact, by reading them back and
 * comparing them to what was written.</p>
 *
 * <p>It lives here rather than beside the other generation tests because writing a WAV needs both
 * the ML and the audio modules, and {@code ar-ml} does not depend on {@code ar-audio}; this module
 * has both. See the module guidance on placing work that combines them.</p>
 *
 * <p>Requires {@code AR_HARDWARE_MEMORY_SCALE=7}, as the other released-weight generation tests do.
 * Gated on the four released weight sets and the exported tokenizer; the output path may be set with
 * {@code AR_SA3_AUDIO_OUT} and otherwise lands in this module's build directory. The test logs a
 * skip and returns when a gated input is absent.</p>
 */
public class StableAudio3WavOutputTest extends TestSuiteBase {

	/** Candidate locations for each released weight set, in the order {@link StableAudio3#small} takes them. */
	private static final String[][] WEIGHT_DIRS = {
			{System.getProperty("AR_SA3_DIT_WEIGHTS", System.getenv("AR_SA3_DIT_WEIGHTS")),
					"/workspace/sa3-dit-weights"},
			{System.getProperty("AR_SA3_CONDITIONER_WEIGHTS", System.getenv("AR_SA3_CONDITIONER_WEIGHTS")),
					"/workspace/sa3-conditioner-weights"},
			{System.getProperty("AR_T5GEMMA_WEIGHTS", System.getenv("AR_T5GEMMA_WEIGHTS")),
					"/workspace/t5gemma-weights"},
			{System.getProperty("AR_SA3_AE_WEIGHTS", System.getenv("AR_SA3_AE_WEIGHTS")),
					"/workspace/sa3-ae-weights"}
	};

	/** Candidate locations for the exported prompt tokenizer. */
	private static final String[] TOKENIZER_PATHS = {
			System.getProperty("AR_T5GEMMA_TOKENIZER", System.getenv("AR_T5GEMMA_TOKENIZER")),
			"/workspace/t5gemma-tokenizer.bin"
	};

	/** Where the clip is written. */
	private static final String OUTPUT = System.getProperty("AR_SA3_AUDIO_OUT",
			System.getenv("AR_SA3_AUDIO_OUT") == null
					? "target/sa3-generated.wav" : System.getenv("AR_SA3_AUDIO_OUT"));

	/** The prompt to generate from. */
	private static final String PROMPT = "a warm analog synth pad with slow filter sweeps";

	/**
	 * Duration to generate, in seconds; {@code AR_SA3_SECONDS} overrides it. The default is short
	 * because the cost is per latent frame, and the model accepts up to
	 * {@code MAX_SAMPLES / SAMPLE_RATE} less {@link StableAudio3#HEADROOM_SECONDS}.
	 */
	private static final double SECONDS = setting("AR_SA3_SECONDS", 2.0);

	/**
	 * Sampling steps; {@code AR_SA3_STEPS} overrides it. The default is below the released
	 * {@link StableAudio3#DEFAULT_STEPS} so an unattended run stays affordable.
	 */
	private static final int STEPS = (int) setting("AR_SA3_STEPS", 4);

	/** Bit depth of the written file. */
	private static final int BITS = 32;

	/** Tolerance of the write-then-read comparison at {@link #BITS}. */
	private static final double TOLERANCE = 1e-6;

	/**
	 * Generates a clip from {@link #PROMPT} and writes it to a WAV file, then reads the file back and
	 * confirms the samples survived.
	 *
	 * @throws IOException if a weight set, the tokenizer, or the output file cannot be read or written
	 */
	@Test(timeout = 2400000)
	public void writesGeneratedAudioToAWavFile() throws IOException {
		File tokenizerFile = firstFile(TOKENIZER_PATHS);
		List<File> dirs = new ArrayList<>();

		for (String[] candidates : WEIGHT_DIRS) {
			dirs.add(firstDirectory(candidates));
		}

		if (tokenizerFile == null || dirs.contains(null)) {
			log("skipping WAV output; gated inputs absent (tokenizer=" + tokenizerFile
					+ ", weights=" + dirs + ")");
			return;
		}

		List<StateDictionary> weights = new ArrayList<>();
		StableAudio3 model = null;
		PackedCollection audio = null;

		try {
			for (File dir : dirs) {
				weights.add(new StateDictionary(dir.getPath()));
			}

			model = StableAudio3.small(weights.get(0), weights.get(1), weights.get(2), weights.get(3),
					SECONDS).setSteps(STEPS);
			model.setTokenizer(new SentencePieceBPETokenizer(tokenizerFile.getPath()));

			log("generating from " + PROMPT);
			audio = model.generateFromText(7, PROMPT, SECONDS).evaluate();

			int channels = audio.getShape().length(0);
			int frames = audio.getShape().length(1);
			File out = new File(OUTPUT);

			if (out.getParentFile() != null) {
				out.getParentFile().mkdirs();
			}

			try (WavFile wav = WavFile.newWavFile(out, channels, frames, BITS,
					StableAudio3.SAMPLE_RATE)) {
				assertEquals(frames, wav.writeFrames(audio));
			}

			log("wrote " + channels + " channels x " + frames + " frames to " + out.getAbsolutePath()
					+ " (" + out.length() + " bytes)");

			assertSurvivedTheRoundTrip(out, audio, channels, frames);
		} finally {
			if (audio != null) {
				audio.destroy();
			}
			if (model != null) {
				model.destroy();
			}

			weights.forEach(StateDictionary::destroy);
		}
	}

	/**
	 * Reads the written file back and compares every sample to what was generated.
	 *
	 * @param out      the written file
	 * @param audio    the generated audio
	 * @param channels the channel count
	 * @param frames   the frame count
	 * @throws IOException if the file cannot be read
	 */
	protected void assertSurvivedTheRoundTrip(File out, PackedCollection audio,
											  int channels, int frames) throws IOException {
		try (WavFile wav = WavFile.openWavFile(out)) {
			assertEquals(channels, wav.getNumChannels());
			assertEquals((long) frames, wav.getNumFrames());
			assertEquals((long) StableAudio3.SAMPLE_RATE, wav.getSampleRate());

			double[][] read = new double[channels][frames];
			assertEquals(frames, wav.readFrames(read, frames));

			double largest = 0;
			for (int c = 0; c < channels; c++) {
				// One bulk transfer per channel; reading sample by sample crosses into native
				// memory once per sample, which dominates the run for a clip of any length.
				double[] generated = audio.toArray(c * frames, frames);

				for (int f = 0; f < frames; f++) {
					largest = Math.max(largest, Math.abs(read[c][f] - generated[f]));
				}
			}

			log(String.format("largest write-read difference %.3e", largest));
			assertTrue("the file does not hold the generated samples (largest difference "
					+ largest + ")", largest <= TOLERANCE);
		}
	}

	/**
	 * A numeric setting, from a system property, then the environment, then the fallback.
	 *
	 * @param name     the property and variable name
	 * @param fallback the value to use when neither is set
	 * @return the setting
	 */
	protected static double setting(String name, double fallback) {
		String value = System.getProperty(name, System.getenv(name));
		return value == null || value.isEmpty() ? fallback : Double.parseDouble(value);
	}

	/**
	 * The first existing candidate file, or {@code null} when none is present.
	 *
	 * @param candidates the paths to try, in order; a {@code null} entry is skipped
	 * @return the first existing file, or {@code null}
	 */
	protected File firstFile(String[] candidates) {
		return first(candidates, false);
	}

	/**
	 * The first existing candidate directory, or {@code null} when none is present.
	 *
	 * @param candidates the paths to try, in order; a {@code null} entry is skipped
	 * @return the first existing directory, or {@code null}
	 */
	protected File firstDirectory(String[] candidates) {
		return first(candidates, true);
	}

	/**
	 * The first candidate path that exists and is of the requested kind.
	 *
	 * @param candidates the paths to try, in order; a {@code null} entry is skipped
	 * @param directory  whether a directory is wanted rather than a file
	 * @return the first match, or {@code null}
	 */
	protected File first(String[] candidates, boolean directory) {
		for (String path : candidates) {
			if (path == null) continue;

			File file = new File(path);
			if (directory ? file.isDirectory() : file.isFile()) return file;
		}

		return null;
	}
}
