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

import org.almostrealism.studio.midi.MoonbeamMidiGenerator;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.music.midi.MidiFileReader;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.studio.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.io.Console;
import org.almostrealism.ml.midi.MoonbeamMidi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone inference runner for the Moonbeam MIDI model.
 *
 * <p>This class provides a {@code main()} method for running Moonbeam MIDI
 * generation from the command line with detailed progress logging, so the
 * user can monitor each step of the process.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Unconditional generation (from SOS)
 * java ... MoonbeamInferenceRunner /path/to/weights output.mid 50
 *
 * # Conditional generation (from a MIDI prompt)
 * java ... MoonbeamInferenceRunner /path/to/weights output.mid 50 --input prompt.mid
 *
 * # With sampling parameters
 * java ... MoonbeamInferenceRunner /path/to/weights output.mid 50 --temp 0.8 --top-p 0.95 --seed 42
 * </pre>
 */
public class MoonbeamInferenceRunner {

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			log("Usage: MoonbeamInferenceRunner <weights_dir> <output.mid> <max_tokens> [options]");
			log("");
			log("Options:");
			log("  --input <file.mid>   MIDI file to use as prompt (default: unconditional)");
			log("  --temp <value>       Sampling temperature (default: 0.8, 0=greedy)");
			log("  --top-p <value>      Nucleus sampling threshold (default: 0.95)");
			log("  --seed <value>       Random seed for reproducibility");
			log("  --config <name>      Model config: 'default' or '309M' (default: 309M)");
			System.exit(1);
		}

		String weightsDir = args[0];
		String outputPath = args[1];
		int maxTokens = Integer.parseInt(args[2]);

		String inputPath = null;
		double temperature = 0.8;
		double topP = 0.95;
		long seed = -1;
		String configName = "309M";

		for (int i = 3; i < args.length; i++) {
			switch (args[i]) {
				case "--input":
					inputPath = args[++i];
					break;
				case "--temp":
					temperature = Double.parseDouble(args[++i]);
					break;
				case "--top-p":
					topP = Double.parseDouble(args[++i]);
					break;
				case "--seed":
					seed = Long.parseLong(args[++i]);
					break;
				case "--config":
					configName = args[++i];
					break;
				default:
					log("Unknown option: " + args[i]);
					System.exit(1);
			}
		}

		log("=== Moonbeam MIDI Inference Runner ===");
		log("Weights directory: " + weightsDir);
		log("Output file: " + outputPath);
		log("Max tokens: " + maxTokens);
		log("Temperature: " + temperature);
		log("Top-p: " + topP);
		log("Config: " + configName);
		if (inputPath != null) {
			log("Input prompt: " + inputPath);
		} else {
			log("Mode: unconditional generation (from SOS)");
		}
		log("");

		// Validate weights directory
		File weightsFile = new File(weightsDir);
		if (!weightsFile.isDirectory()) {
			log("ERROR: Weights directory not found: " + weightsDir);
			log("Run extract_moonbeam_weights.py first to create protobuf weights.");
			System.exit(1);
		}

		// Select config
		MoonbeamConfig config;
		if ("309M".equalsIgnoreCase(configName)) {
			config = MoonbeamConfig.checkpoint309M();
		} else {
			config = MoonbeamConfig.defaultConfig();
		}
		log("Model config: " + config);
		log("");

		// Load model
		log("[1/4] Loading model weights...");
		long startLoad = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(weightsDir, config);
		long loadTime = System.currentTimeMillis() - startLoad;
		log("[1/4] Model loaded and compiled in " + formatTime(loadTime));
		log("");

		// Create autoregressive model
		MoonbeamMidiGenerator gen = new MoonbeamMidiGenerator(model);
		gen.setTemperature(temperature);
		gen.setTopP(topP);
		if (seed >= 0) {
			gen.setSeed(seed);
			log("Random seed: " + seed);
		}

		// Set prompt if provided
		if (inputPath != null) {
			log("[2/4] Loading MIDI prompt: " + inputPath);
			long startPrompt = System.currentTimeMillis();

			MidiFileReader reader = new MidiFileReader();
			MidiTokenizer tokenizer = new MidiTokenizer();
			List<MidiNoteEvent> inputEvents = reader.read(new File(inputPath));
			List<MidiCompoundToken> inputTokens = tokenizer.tokenize(inputEvents);

			log("  Prompt: " + inputEvents.size() + " note events -> "
					+ inputTokens.size() + " tokens (incl. SOS/EOS)");

			// Remove the trailing EOS since we want to continue generating
			List<MidiCompoundToken> promptTokens = new ArrayList<>(inputTokens);
			if (!promptTokens.isEmpty() && promptTokens.get(promptTokens.size() - 1).isEOS()) {
				promptTokens.remove(promptTokens.size() - 1);
			}

			gen.setPrompt(promptTokens.toArray(new MidiCompoundToken[0]));

			log("[2/4] Processing prompt through transformer...");
			long promptProcessStart = System.currentTimeMillis();
			// Process prompt tokens (builds KV cache)
			for (int i = 0; i < promptTokens.size(); i++) {
				gen.next();
				if ((i + 1) % 10 == 0 || i == promptTokens.size() - 1) {
					long elapsed = System.currentTimeMillis() - promptProcessStart;
					log("  Prompt token " + (i + 1) + "/" + promptTokens.size()
							+ " (" + formatTime(elapsed) + " elapsed)");
				}
			}

			long promptTime = System.currentTimeMillis() - startPrompt;
			log("[2/4] Prompt processed in " + formatTime(promptTime));
		} else {
			log("[2/4] Skipping prompt (unconditional generation)");
		}
		log("");

		// Generate tokens
		log("[3/4] Generating " + maxTokens + " tokens...");
		long startGen = System.currentTimeMillis();
		List<MidiCompoundToken> generated = new ArrayList<>();

		for (int i = 0; i < maxTokens; i++) {
			long tokenStart = System.currentTimeMillis();
			MidiCompoundToken token = gen.next();
			long tokenTime = System.currentTimeMillis() - tokenStart;

			generated.add(token);

			if (token.isEOS()) {
				log("  Token " + (i + 1) + ": EOS (generation complete) [" + formatTime(tokenTime) + "]");
				break;
			}

			String tokenDesc = String.format(
					"onset=%d, dur=%d, oct=%d, pc=%d, inst=%d, vel=%d",
					token.getOnset(), token.getDuration(), token.getOctave(),
					token.getPitchClass(), token.getInstrument(), token.getVelocity());
			log("  Token " + (i + 1) + "/" + maxTokens + ": " + tokenDesc
					+ " [" + formatTime(tokenTime) + "]");

			if ((i + 1) % 10 == 0) {
				long elapsed = System.currentTimeMillis() - startGen;
				double tokensPerSec = (i + 1) / (elapsed / 1000.0);
				int remaining = maxTokens - (i + 1);
				double etaSec = remaining / tokensPerSec;
				log("  --- Progress: " + (i + 1) + "/" + maxTokens
						+ " | " + String.format("%.2f", tokensPerSec) + " tok/s"
						+ " | ETA: " + formatTime((long) (etaSec * 1000)) + " ---");
			}
		}

		long genTime = System.currentTimeMillis() - startGen;
		int genCount = generated.size();
		double tokensPerSec = genCount / (genTime / 1000.0);
		log("[3/4] Generated " + genCount + " tokens in " + formatTime(genTime)
				+ " (" + String.format("%.2f", tokensPerSec) + " tok/s)");
		log("");

		// Write output MIDI
		log("[4/4] Writing MIDI output to " + outputPath);
		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> outputTokens = new ArrayList<>();

		// Filter out special tokens
		for (MidiCompoundToken token : generated) {
			if (!token.isEOS() && !token.isSOS() && !token.isPAD()) {
				outputTokens.add(token);
			}
		}

		if (outputTokens.isEmpty()) {
			log("WARNING: No non-special tokens generated. Output will be empty.");
		}

		List<MidiNoteEvent> events = tokenizer.detokenize(outputTokens);
		MidiFileReader writer = new MidiFileReader();
		writer.write(events, new File(outputPath));

		log("[4/4] Wrote " + events.size() + " MIDI note events to " + outputPath);
		log("");
		log("=== Inference complete ===");
		log("Profile summary:");
		log(model.getProfilingSummary());
	}

	private static void log(String message) {
		String timestamp = String.format("[%tT]", System.currentTimeMillis());
		Console.root().println(timestamp + " " + message);
	}

	private static String formatTime(long millis) {
		if (millis < 1000) return millis + "ms";
		if (millis < 60000) return String.format("%.1fs", millis / 1000.0);
		long minutes = millis / 60000;
		double seconds = (millis % 60000) / 1000.0;
		return String.format("%dm %.1fs", minutes, seconds);
	}
}
