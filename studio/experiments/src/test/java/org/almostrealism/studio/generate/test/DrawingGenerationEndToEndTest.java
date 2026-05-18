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

package org.almostrealism.studio.generate.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.SentencePieceTokenizer;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;
import org.almostrealism.ml.audio.AudioAttentionConditioner;
import org.almostrealism.ml.audio.OnnxAudioConditioner;
import org.almostrealism.ml.audio.OnnxAutoEncoder;
import org.almostrealism.persist.assets.Asset;
import org.almostrealism.persist.assets.AssetGroup;
import org.almostrealism.studio.generate.AudioModel;
import org.almostrealism.studio.ml.AudioGenerator;
import org.almostrealism.studio.ml.AutoEncoderFeatureProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * End-to-end test that exercises the complete drawing-to-audio generation
 * pipeline and produces a WAV file. Requires the ONNX model assets to be
 * present on disk.
 *
 * <p>This test replicates what the app does when a user draws frequency data
 * and clicks Generate:</p>
 * <ol>
 *   <li>Create a {@link WaveDetails} with frequency data (simulating a drawing)</li>
 *   <li>Include it in the {@link AudioLibrary}</li>
 *   <li>Call {@link AudioLibrary#getDetailsAwait} to compute features
 *       (freq &rarr; audio &rarr; autoencoder)</li>
 *   <li>Feed features to {@link AudioGenerator}</li>
 *   <li>Run diffusion sampling</li>
 *   <li>Decode latent and save WAV</li>
 * </ol>
 *
 * @see AudioGenerator
 * @see AutoEncoderFeatureProvider
 */
public class DrawingGenerationEndToEndTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;
	private static final int FREQ_BINS = 256;
	private static final int FREQ_FRAMES = 1000;
	private static final double FREQ_SAMPLE_RATE = 100.0;

	private static final String AUTOENCODER_DIR =
			System.getProperty("user.home") +
					"/Documents/AlmostRealism/Resources/assets/stable-audio-autoencoder";
	private static final String DIT_DIR =
			System.getProperty("user.home") +
					"/Library/Application Support/com.almostrealism.Rings/assets/stable-audio-dit";

	private static final String RESULTS_DIR = "results";

	private Path tempDir;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("e2e-generation-test");
		new File(RESULTS_DIR).mkdirs();
	}

	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		}
	}

	/**
	 * Full pipeline test: drawing freq data &rarr; features &rarr;
	 * generation &rarr; WAV file.
	 */
	@Test(timeout = 600000)
	public void generateAudioFromDrawing() throws Exception {
		File autoEncoderDir = new File(AUTOENCODER_DIR);
		File ditDir = new File(DIT_DIR);
		if (!autoEncoderDir.exists() || !ditDir.exists()) {
			log("Skipping: model assets not found");
			return;
		}

		AssetGroup autoEncoderAssets = assetGroupFromDir(autoEncoderDir);
		AssetGroup ditAssets = assetGroupFromDir(ditDir);

		AudioLibrary library = new AudioLibrary(tempDir.toFile(), SAMPLE_RATE);
		OnnxAutoEncoder autoEncoder = new OnnxAutoEncoder(autoEncoderAssets);
		library.getWaveDetailsFactory().setFeatureProvider(
				new AutoEncoderFeatureProvider(autoEncoder));

		try {
			WaveDetails drawing = createDrawingDetails("e2e-drawing-id");
			library.include(drawing);
			log("Drawing included with freqData shape: " +
					drawing.getFreqData().getShape());

			WaveDetails completed = library.getDetailsAwait("e2e-drawing-id", 300);
			Assert.assertNotNull("getDetailsAwait must return a result", completed);
			Assert.assertNotNull("Audio data must be synthesized", completed.getData());
			Assert.assertNotNull("Feature data must be computed", completed.getFeatureData());
			log("Feature data shape: " + completed.getFeatureData().getShape());

			PackedCollection featureData = completed.getFeatureData(true);
			log("Transposed feature data shape: " + featureData.getShape());
			Assert.assertEquals("Feature data must be 2D", 2,
					featureData.getShape().getDimensions());

			Tokenizer tokenizer = new SentencePieceTokenizer();
			AudioAttentionConditioner conditioner = new OnnxAudioConditioner(ditAssets);
			StateDictionary stateDictionary = new StateDictionary(ditAssets.getAllAssets());
			AudioGenerator generator = new AudioGenerator(
					tokenizer, conditioner, autoEncoder, stateDictionary,
					AudioModel.DIM, 42);
			generator.setAudioDurationSeconds(2.0);
			generator.setStrength(0.4);
			generator.addFeatures(featureData);

			PackedCollection position = new PackedCollection(AudioModel.DIM);
			for (int i = 0; i < AudioModel.DIM; i++) {
				position.setMem(i, Math.random());
			}

			String outputPath = tempDir.resolve("generated_output.wav").toString();
			generator.generateAudio(position, "ambient pad", 42L, outputPath);

			File outputFile = new File(outputPath);
			Assert.assertTrue("Output WAV file must exist", outputFile.exists());
			Assert.assertTrue("Output WAV file must not be empty",
					outputFile.length() > 1000);

			Path resultsOutput = Path.of(RESULTS_DIR, "drawing-generation-e2e.wav");
			Files.copy(outputFile.toPath(), resultsOutput,
					StandardCopyOption.REPLACE_EXISTING);
			log("Generated WAV: " + resultsOutput + " (" + outputFile.length() + " bytes)");
		} finally {
			library.stop();
		}
	}

	/**
	 * Creates a {@link WaveDetails} simulating a spatial drawing with
	 * frequency data at the same dimensions as the real drawing canvas.
	 */
	private WaveDetails createDrawingDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier, SAMPLE_RATE);
		details.setFreqBinCount(FREQ_BINS);
		details.setFreqFrameCount(FREQ_FRAMES);
		details.setFreqSampleRate(FREQ_SAMPLE_RATE);
		details.setFreqChannelCount(1);
		details.setFrameCount((int) (FREQ_FRAMES * SAMPLE_RATE / FREQ_SAMPLE_RATE));

		PackedCollection freqData = new PackedCollection(FREQ_FRAMES * FREQ_BINS);
		for (int f = 0; f < FREQ_FRAMES; f++) {
			for (int b = 0; b < FREQ_BINS; b++) {
				double t = (double) f / FREQ_FRAMES;
				double freq = (double) b / FREQ_BINS;

				double value = 0;
				value += Math.exp(-0.5 * Math.pow((freq - 0.12) / 0.02, 2)) * 5.0;
				value += Math.exp(-0.5 * Math.pow((freq - 0.24) / 0.02, 2)) * 3.0;
				value += Math.exp(-0.5 * Math.pow((freq - 0.36) / 0.02, 2)) * 1.5;
				value *= Math.sin(Math.PI * t);

				freqData.setMem(f * FREQ_BINS + b, Math.max(0, value));
			}
		}
		details.setFreqData(freqData);

		return details;
	}

	/** Creates an {@link AssetGroup} from all files in a directory. */
	private static AssetGroup assetGroupFromDir(File dir) {
		List<Asset> assets = Arrays.stream(Objects.requireNonNull(dir.listFiles()))
				.filter(File::isFile)
				.filter(f -> !f.getName().startsWith("."))
				.map(Asset::new)
				.toList();
		return new AssetGroup(assets);
	}
}
