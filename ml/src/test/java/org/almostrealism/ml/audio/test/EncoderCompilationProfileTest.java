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

package org.almostrealism.ml.audio.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.OobleckEncoder;
import org.almostrealism.ml.audio.VAEBottleneck;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Profile test to identify performance bottlenecks in encoder compilation.
 *
 * <p>This test creates performance profile XML files that can be analyzed
 * with the ar-profile-analyzer MCP tools to identify what's causing
 * slow compilation of the encoder for audio processing.</p>
 */
public class EncoderCompilationProfileTest extends TestSuiteBase {

	private static final Path AUTOENCODER_DIR = Path.of("/workspace/project/weights/autoencoder");
	private static final int SAMPLE_RATE = 44100;

	/**
	 * Profile encoder compilation with 1-second audio (small).
	 * This should complete relatively quickly.
	 */
	@Test
	public void profileEncoderSmall() throws IOException {
		profileEncoder(1.0, "encoder_1sec");
	}

	/**
	 * Profile encoder compilation with 2-second audio (medium).
	 */
	@Test
	public void profileEncoderMedium() throws IOException {
		profileEncoder(2.0, "encoder_2sec");
	}

	/**
	 * Profile encoder compilation with 5-second audio (large - the problematic case).
	 */
	@Test
	public void profileEncoderLarge() throws IOException {
		profileEncoder(5.0, "encoder_5sec");
	}

	/**
	 * Profile just the encoder model construction (no compilation).
	 */
	@Test
	public void profileEncoderConstruction() throws IOException {
		if (!Files.exists(AUTOENCODER_DIR)) {
			log("Autoencoder weights not found at " + AUTOENCODER_DIR + ", skipping");
			return;
		}

		log("=== Profiling Encoder Construction (no compilation) ===");
		StateDictionary weights = new StateDictionary(AUTOENCODER_DIR.toString());

		int segmentSamples = (int) (5.0 * SAMPLE_RATE);
		log("Segment samples: " + segmentSamples);

		OperationProfileNode profile = new OperationProfileNode("encoder_construction");

		profile(profile, () -> {
			log("Creating OobleckEncoder...");
			long start = System.currentTimeMillis();
			OobleckEncoder encoder = new OobleckEncoder(weights, 1, segmentSamples);
			log("OobleckEncoder created in " + (System.currentTimeMillis() - start) + " ms");
			log("Output length: " + encoder.getOutputLength());

			log("Creating VAEBottleneck...");
			start = System.currentTimeMillis();
			VAEBottleneck bottleneck = new VAEBottleneck(1, encoder.getOutputLength());
			log("VAEBottleneck created in " + (System.currentTimeMillis() - start) + " ms");

			log("Creating Model and adding layers...");
			start = System.currentTimeMillis();
			Model model = new Model(new TraversalPolicy(1, 2, segmentSamples));
			model.add(encoder);
			model.add(bottleneck.getBottleneck());
			log("Model layers added in " + (System.currentTimeMillis() - start) + " ms");
		});

		String profilePath = "results/encoder_construction.xml";
		profile.save(profilePath);
		log("Profile saved to: " + profilePath);
	}

	/**
	 * Profile just the compilation step (after model is built).
	 */
	@Test
	public void profileEncoderCompilationOnly() throws IOException {
		if (!Files.exists(AUTOENCODER_DIR)) {
			log("Autoencoder weights not found at " + AUTOENCODER_DIR + ", skipping");
			return;
		}

		log("=== Profiling Encoder Compilation Only ===");
		StateDictionary weights = new StateDictionary(AUTOENCODER_DIR.toString());

		// Use smaller audio for faster construction
		double seconds = 1.0;
		int segmentSamples = (int) (seconds * SAMPLE_RATE);
		log("Segment samples: " + segmentSamples + " (" + seconds + " seconds)");

		// Build model first (outside profiling)
		log("Building encoder model...");
		long buildStart = System.currentTimeMillis();
		OobleckEncoder encoder = new OobleckEncoder(weights, 1, segmentSamples);
		VAEBottleneck bottleneck = new VAEBottleneck(1, encoder.getOutputLength());
		Model model = new Model(new TraversalPolicy(1, 2, segmentSamples));
		model.add(encoder);
		model.add(bottleneck.getBottleneck());
		log("Model built in " + (System.currentTimeMillis() - buildStart) + " ms");

		// Profile compilation
		OperationProfileNode profile = new OperationProfileNode("encoder_compilation");

		profile(profile, () -> {
			log("Compiling model (backprop=false)...");
			long start = System.currentTimeMillis();
			CompiledModel compiled = model.compile(false);
			log("Compilation completed in " + (System.currentTimeMillis() - start) + " ms");
		});

		String profilePath = "results/encoder_compilation_only.xml";
		profile.save(profilePath);
		log("Profile saved to: " + profilePath);
	}

	private void profileEncoder(double seconds, String profileName) throws IOException {
		if (!Files.exists(AUTOENCODER_DIR)) {
			log("Autoencoder weights not found at " + AUTOENCODER_DIR + ", skipping");
			return;
		}

		log("=== Profiling Encoder: " + seconds + " seconds ===");
		StateDictionary weights = new StateDictionary(AUTOENCODER_DIR.toString());

		int segmentSamples = (int) (seconds * SAMPLE_RATE);
		log("Segment samples: " + segmentSamples);

		OperationProfileNode profile = new OperationProfileNode(profileName);

		profile(profile, () -> {
			log("Creating OobleckEncoder...");
			long start = System.currentTimeMillis();
			OobleckEncoder encoder = new OobleckEncoder(weights, 1, segmentSamples);
			log("OobleckEncoder created in " + (System.currentTimeMillis() - start) + " ms");
			log("Output length: " + encoder.getOutputLength());

			log("Creating VAEBottleneck...");
			start = System.currentTimeMillis();
			VAEBottleneck bottleneck = new VAEBottleneck(1, encoder.getOutputLength());
			log("VAEBottleneck created in " + (System.currentTimeMillis() - start) + " ms");

			log("Building model...");
			start = System.currentTimeMillis();
			Model model = new Model(new TraversalPolicy(1, 2, segmentSamples));
			model.add(encoder);
			model.add(bottleneck.getBottleneck());
			log("Model built in " + (System.currentTimeMillis() - start) + " ms");

			log("Compiling model (backprop=false)...");
			start = System.currentTimeMillis();
			CompiledModel compiled = model.compile(false);
			log("Compilation completed in " + (System.currentTimeMillis() - start) + " ms");
		});

		String profilePath = "results/" + profileName + ".xml";
		profile.save(profilePath);
		log("Profile saved to: " + profilePath);

		weights.destroy();
	}
}
