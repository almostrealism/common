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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Demonstrates the PDSL DSP pipeline by processing audio signals and writing
 * {@code .wav} files using {@link WavFile}.
 *
 * <p>This test lives in the {@code studio/compose} module where both PDSL
 * infrastructure ({@code ar-ml}) and audio file writing ({@code ar-audio})
 * are available, allowing the use of the real {@link WavFile} API rather than
 * a hand-rolled ByteBuffer writer.</p>
 *
 * <p>The PDSL primitives themselves ({@code PdslLoader}, {@code PdslInterpreter})
 * remain in the {@code engine/ml} module. Only this integration test has moved.</p>
 *
 * @see org.almostrealism.ml.dsl.PdslInterpreter
 * @see WavFile
 */
public class PdslAudioDemoTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Audio buffer size used across tests. */
	private static final int SIGNAL_SIZE = 256;

	/** Sample rate used across tests (Hz). */
	private static final int SAMPLE_RATE = 44100;

	/** Filter order matching EfxManager.filterOrder. */
	private static final int FILTER_ORDER = 40;

	/** Low-pass cutoff for tests (Hz). */
	private static final double LP_CUTOFF = 5000.0;

	/**
	 * Demonstrates the PDSL DSP pipeline by processing audio signals and writing
	 * {@code .wav} files to {@code results/pdsl-audio-dsp/} for human review.
	 *
	 * <p>Two demonstrations:
	 * <ol>
	 *   <li><b>Low-pass FIR filter</b> — a multi-tone input (440 Hz + 2 kHz + 12 kHz)
	 *       passes through the {@code efx_lowpass_wet} PDSL layer. The 12 kHz component
	 *       is strongly attenuated by the 5 kHz LP filter, producing audibly filtered output.</li>
	 *   <li><b>Delay line</b> — a 440 Hz sine that stops at 0.5 s passes through the
	 *       {@code efx_delay} PDSL layer. The delay-line buffer persists across
	 *       {@link CompiledModel#forward} calls, producing a delayed-echo effect.</li>
	 * </ol>
	 *
	 * <p>The {@code .wav} files are not committed; they are left for human review to
	 * confirm that the PDSL-defined pipeline produces audible output.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testPdslDspProducesAudio() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		int totalSamples = SAMPLE_RATE; // 1 second at 44100 Hz
		int numPasses = totalSamples / SIGNAL_SIZE;
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		// ---- Demo 1: PDSL low-pass FIR filter ----
		Map<String, Object> lpArgs = new HashMap<>();
		lpArgs.put("signal_size", SIGNAL_SIZE);
		lpArgs.put("cutoff", LP_CUTOFF);
		lpArgs.put("sample_rate", (double) SAMPLE_RATE);
		lpArgs.put("filter_order", (double) FILTER_ORDER);
		lpArgs.put("wet_level", 1.0);
		Block lpBlock = loader.buildLayer(program, "efx_lowpass_wet", inputShape, lpArgs);
		Model lpModel = new Model(inputShape);
		lpModel.add(lpBlock);
		CompiledModel lpCompiled = lpModel.compile();

		float[] drySignal = new float[totalSamples];
		float[] lpSignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			final int sampleOffset = pass * SIGNAL_SIZE;
			PackedCollection input = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (sampleOffset + i) / SAMPLE_RATE;
				return 0.33 * Math.sin(2.0 * Math.PI * 440.0 * t)
						+ 0.33 * Math.sin(2.0 * Math.PI * 2000.0 * t)
						+ 0.33 * Math.sin(2.0 * Math.PI * 12000.0 * t);
			});
			PackedCollection output = lpCompiled.forward(input);
			double[] inArr = input.toArray(0, SIGNAL_SIZE);
			double[] outArr = output.toArray(0, SIGNAL_SIZE);
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				drySignal[sampleOffset + i] = (float) inArr[i];
				lpSignal[sampleOffset + i] = (float) outArr[i];
			}
		}

		writeDemoWav(new File(outputDir, "pdsl_dry_multitone.wav"), drySignal, SAMPLE_RATE);
		writeDemoWav(new File(outputDir, "pdsl_lowpass_multitone.wav"), lpSignal, SAMPLE_RATE);
		Assert.assertTrue("Dry WAV must be non-empty",
				new File(outputDir, "pdsl_dry_multitone.wav").length() > 0);
		Assert.assertTrue("Lowpass WAV must be non-empty",
				new File(outputDir, "pdsl_lowpass_multitone.wav").length() > 0);

		// LP output must have less energy than dry (12 kHz content attenuated)
		int skipEdge = FILTER_ORDER / 2;
		double dryEnergy = energy(floatToDouble(drySignal), skipEdge);
		double lpEnergy = energy(floatToDouble(lpSignal), skipEdge);
		Assert.assertTrue("LP output must have less energy than dry (LP attenuates 12 kHz)",
				lpEnergy < dryEnergy * 0.9);

		// ---- Demo 2: PDSL delay line ----
		// Buffer size equals signal size so the circular buffer holds exactly one frame.
		int delaySamples = SIGNAL_SIZE / 4;
		PackedCollection delayBuffer = new PackedCollection(SIGNAL_SIZE);
		delayBuffer.setMem(new double[SIGNAL_SIZE]);
		PackedCollection delayHead = new PackedCollection(1);
		delayHead.setMem(new double[]{0.0});

		Map<String, Object> delayArgs = new HashMap<>();
		delayArgs.put("signal_size", SIGNAL_SIZE);
		delayArgs.put("delay_samples", delaySamples);
		delayArgs.put("buffer", delayBuffer);
		delayArgs.put("head", delayHead);

		Block delayBlock = loader.buildLayer(program, "efx_delay", inputShape, delayArgs);
		Model delayModel = new Model(inputShape);
		delayModel.add(delayBlock);
		CompiledModel delayCompiled = delayModel.compile();

		float[] delaySignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			final int sampleOffset = pass * SIGNAL_SIZE;
			PackedCollection input = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (sampleOffset + i) / SAMPLE_RATE;
				return t < 0.5 ? Math.sin(2.0 * Math.PI * 440.0 * t) : 0.0;
			});
			PackedCollection output = delayCompiled.forward(input);
			double[] outArr = output.toArray(0, SIGNAL_SIZE);
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				delaySignal[sampleOffset + i] = (float) outArr[i];
			}
		}

		writeDemoWav(new File(outputDir, "pdsl_delay_echo.wav"), delaySignal, SAMPLE_RATE);
		Assert.assertTrue("Delay WAV must be non-empty",
				new File(outputDir, "pdsl_delay_echo.wav").length() > 0);
	}

	/**
	 * Demonstrates wet/dry mixing using {@code accum_blocks + identity} in PDSL.
	 *
	 * <p>Processes a 440 Hz sine through the {@code efx_wet_dry_mix} layer, which
	 * combines the dry (pass-through) signal with a 50 % wet delayed copy using
	 * {@code accum_blocks({ identity() }, { delay(...); scale(0.5) })}. No separate
	 * {@code mix} primitive is needed — the composition already expresses it.</p>
	 *
	 * <p>The output WAV {@code pdsl_wet_dry_mix.wav} should sound like the dry sine
	 * with an audible echo approximately a quarter-buffer offset behind it.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testPdslMixDemo() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		int delaySamples = SIGNAL_SIZE / 4;
		double wetLevel = 0.5;

		PackedCollection mixBuffer = new PackedCollection(SIGNAL_SIZE);
		mixBuffer.setMem(new double[SIGNAL_SIZE]);
		PackedCollection mixHead = new PackedCollection(1);
		mixHead.setMem(new double[]{0.0});

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> mixArgs = new HashMap<>();
		mixArgs.put("signal_size", SIGNAL_SIZE);
		mixArgs.put("delay_samples", delaySamples);
		mixArgs.put("wet_level", wetLevel);
		mixArgs.put("buffer", mixBuffer);
		mixArgs.put("head", mixHead);

		Block mixBlock = loader.buildLayer(program, "efx_wet_dry_mix", inputShape, mixArgs);
		Model mixModel = new Model(inputShape);
		mixModel.add(mixBlock);
		CompiledModel mixCompiled = mixModel.compile();

		float[] drySignal = new float[totalSamples];
		float[] mixSignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			final int sampleOffset = pass * SIGNAL_SIZE;
			PackedCollection input = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (sampleOffset + i) / SAMPLE_RATE;
				return Math.sin(2.0 * Math.PI * 440.0 * t);
			});
			PackedCollection output = mixCompiled.forward(input);
			double[] inArr = input.toArray(0, SIGNAL_SIZE);
			double[] outArr = output.toArray(0, SIGNAL_SIZE);
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				drySignal[sampleOffset + i] = (float) inArr[i];
				mixSignal[sampleOffset + i] = (float) outArr[i];
			}
		}

		writeDemoWav(new File(outputDir, "pdsl_wet_dry_mix.wav"), mixSignal, SAMPLE_RATE);
		Assert.assertTrue("Mix WAV must be non-empty",
				new File(outputDir, "pdsl_wet_dry_mix.wav").length() > 0);

		// The mix output must have more energy than the wet-only path
		// (dry signal is preserved at full level, wet is at 0.5 level).
		// The energy of the sum (dry + 0.5 * delayed) > energy of dry alone
		// would not hold in general, but we can at least check the file exists
		// and the output differs from the raw input.
		double diffEnergy = 0.0;
		for (int i = 0; i < totalSamples; i++) {
			double diff = mixSignal[i] - drySignal[i];
			diffEnergy += diff * diff;
		}
		Assert.assertTrue("Mix output must differ from dry (wet path contributes delay echo)",
				diffEnergy > 0.0);
	}

	/**
	 * Writes a mono 16-bit PCM WAV file with the given samples using {@link WavFile}.
	 *
	 * @param file       the file to create (parent directories must exist)
	 * @param samples    the audio samples in the range {@code [-1.0, 1.0]}
	 * @param sampleRate the audio sample rate in Hz
	 * @throws IOException if the file cannot be written
	 */
	static void writeDemoWav(File file, float[] samples, int sampleRate) throws IOException {
		double[] data = new double[samples.length];
		for (int i = 0; i < samples.length; i++) {
			data[i] = samples[i];
		}
		try (WavFile wav = WavFile.newWavFile(file, 1, samples.length, 16, sampleRate)) {
			wav.writeFrames(data, samples.length);
		}
	}
}
