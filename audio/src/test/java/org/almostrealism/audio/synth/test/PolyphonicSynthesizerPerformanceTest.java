/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.synth.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.line.BufferOutputLine;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.PolyphonicSynthesizer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Performance profiling tests for {@link PolyphonicSynthesizer}.
 * <p>
 * These tests measure the performance characteristics of the synthesizer
 * pipeline and generate XML profile data that can be analyzed using the
 * MCP profile analyzer tools.
 * <p>
 * Key metrics captured:
 * <ul>
 *   <li>Per-tick execution time</li>
 *   <li>Operation breakdown (oscillators, filters, envelopes, mixing)</li>
 *   <li>Memory allocation patterns</li>
 *   <li>Pipeline stage timings</li>
 * </ul>
 * <p>
 * To run and analyze:
 * <pre>
 * # Run test
 * mvn test -pl audio -Dtest=PolyphonicSynthesizerPerformanceTest#profileSynthTicks
 *
 * # Analyze with MCP tools
 * mcp__ar-profile-analyzer__load_profile path: "audio/results/synthPerf.xml"
 * mcp__ar-profile-analyzer__find_slowest path: "audio/results/synthPerf.xml" limit: 10
 * </pre>
 */
public class PolyphonicSynthesizerPerformanceTest extends TestSuiteBase implements CellFeatures {

	/** Default number of synthesizer voices */
	public static final int VOICE_COUNT = 8;

	/** Sample rate for audio processing */
	public static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Directory for profile output files */
	public static final String RESULTS_DIR = "results";

	/**
	 * Profiles the tick operation of a PolyphonicSynthesizer.
	 * This test measures per-tick performance without BufferedOutputScheduler overhead.
	 */
	@Test
	public void profileSynthTicks() throws IOException {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		ensureResultsDirectory();

		log("=== PolyphonicSynthesizer Tick Performance Profile ===");
		log("Voice count: " + VOICE_COUNT);
		log("Sample rate: " + SAMPLE_RATE);

		// Create profiler
		OperationProfileNode profile = new OperationProfileNode("PolyphonicSynthesizer Tick Performance");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			// Create synthesizer
			PolyphonicSynthesizer synth = createConfiguredSynth();

			// Create output destination
			int bufferSize = 1024;
			PackedCollection destination = new PackedCollection(bufferSize);

			// Set up CellList with synth
			CellList cells = new CellList();
			cells.addRoot(synth);

			// Map to output buffer
			org.almostrealism.audio.WaveOutput waveOutput = new org.almostrealism.audio.WaveOutput(p(destination));
			waveOutput.setCircular(true);
			synth.setReceptor(waveOutput.getWriterCell(0));

			// Compile operations
			log("\nCompiling operations...");
			long compileStart = System.currentTimeMillis();
			Runnable setup = synth.setup().get();
			Runnable tick = synth.tick().get();
			long compileEnd = System.currentTimeMillis();
			log("Compilation time: " + (compileEnd - compileStart) + "ms");

			// Run setup
			log("\nRunning setup...");
			setup.run();

			// Warm up (no notes)
			log("Warming up (silent)...");
			for (int i = 0; i < 100; i++) {
				tick.run();
			}

			// Trigger notes
			log("\nTriggering " + 4 + " notes...");
			synth.noteOn(60, 0.8);  // C4
			synth.noteOn(64, 0.7);  // E4
			synth.noteOn(67, 0.6);  // G4
			synth.noteOn(72, 0.5);  // C5
			log("Active voices: " + synth.getActiveVoiceCount());

			// Profile ticks with active notes
			log("\nProfiling " + 1000 + " ticks with active notes...");
			long tickStart = System.nanoTime();
			for (int i = 0; i < 1000; i++) {
				tick.run();
			}
			long tickEnd = System.nanoTime();
			double tickTimeMs = (tickEnd - tickStart) / 1_000_000.0;
			double avgTickTimeUs = (tickEnd - tickStart) / 1000.0 / 1000.0;

			// Calculate realtime factor
			// 1000 ticks at 1 sample/tick = 1000 samples
			// At 44100 Hz, 1000 samples = 22.68ms of audio
			double audioTimeMs = 1000.0 / SAMPLE_RATE * 1000;
			double realtimeFactor = audioTimeMs / tickTimeMs;

			log("\n=== Tick Performance Results ===");
			log("Total ticks: 1000");
			log("Total time: " + String.format("%.2f", tickTimeMs) + " ms");
			log("Average tick time: " + String.format("%.2f", avgTickTimeUs) + " us");
			log("Audio time represented: " + String.format("%.2f", audioTimeMs) + " ms");
			log("Realtime factor: " + String.format("%.2f", realtimeFactor) + "x");

			if (realtimeFactor < 1.0) {
				log("WARNING: Slower than realtime! Audio will underrun.");
			} else {
				log("OK: Faster than realtime (" + String.format("%.1f", realtimeFactor) + "x headroom)");
			}

			// Release notes
			synth.noteOff(60);
			synth.noteOff(64);
			synth.noteOff(67);
			synth.noteOff(72);

			destination.destroy();

		} finally {
			// Save profile
			String profilePath = RESULTS_DIR + "/synthTickPerf.xml";
			profile.save(profilePath);
			log("\nProfile saved to: " + profilePath);
			log("Analyze with: mcp__ar-profile-analyzer__load_profile path: \"audio/" + profilePath + "\"");
		}
	}

	/**
	 * Profiles the complete audio pipeline including BufferedOutputScheduler.
	 * This measures end-to-end performance for real-time audio output.
	 */
	@Test
	public void profileSchedulerPipeline() throws IOException {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		ensureResultsDirectory();

		log("=== Scheduler Pipeline Performance Profile ===");
		log("Voice count: " + VOICE_COUNT);
		log("Sample rate: " + SAMPLE_RATE);

		// Create profiler
		OperationProfileNode profile = new OperationProfileNode("PolyphonicSynthesizer Scheduler Pipeline");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			// Create synthesizer
			PolyphonicSynthesizer synth = createConfiguredSynth();

			// Create CellList
			CellList cells = new CellList();
			cells.addRoot(synth);

			// Create buffer output
			int captureFrames = SAMPLE_RATE;  // 1 second buffer
			BufferOutputLine bufferLine = new BufferOutputLine(captureFrames);
			bufferLine.setCircular(false);

			// Create scheduler with small frames per tick
			int framesPerTick = 512;
			log("Frames per tick: " + framesPerTick);

			BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
					null, bufferLine, framesPerTick, cells.toLineOperation());

			// Trigger note before starting
			log("\nTriggering chord...");
			synth.noteOn(60, 0.8);
			synth.noteOn(64, 0.7);
			synth.noteOn(67, 0.6);
			synth.noteOn(72, 0.5);

			// Start scheduler
			log("\nStarting scheduler...");
			long startTime = System.currentTimeMillis();
			scheduler.start();

			// Wait for specified number of ticks
			int targetTicks = 100;
			log("Waiting for " + targetTicks + " ticks...");

			while (scheduler.getRenderedCount() < targetTicks) {
				Thread.sleep(10);
				if (System.currentTimeMillis() - startTime > 60000) {
					log("Timeout waiting for ticks!");
					break;
				}
			}

			long endTime = System.currentTimeMillis();
			long elapsed = endTime - startTime;

			// Stop scheduler
			scheduler.stop();

			// Calculate performance
			long renderedTicks = scheduler.getRenderedCount();
			long renderedFrames = scheduler.getRenderedFrames();
			double audioSeconds = (double) renderedFrames / SAMPLE_RATE;
			double wallSeconds = elapsed / 1000.0;
			double realtimeFactor = audioSeconds / wallSeconds;

			log("\n=== Scheduler Pipeline Results ===");
			log("Rendered ticks: " + renderedTicks);
			log("Rendered frames: " + renderedFrames);
			log("Audio time: " + String.format("%.3f", audioSeconds) + " seconds");
			log("Wall time: " + String.format("%.3f", wallSeconds) + " seconds");
			log("Realtime factor: " + String.format("%.2f", realtimeFactor) + "x");
			log("Avg tick time: " + String.format("%.2f", (double) elapsed / renderedTicks) + " ms");
			log("Required tick time for realtime: " + String.format("%.2f", framesPerTick * 1000.0 / SAMPLE_RATE) + " ms");

			if (realtimeFactor < 1.0) {
				log("WARNING: Slower than realtime! Audio will underrun.");
				log("Need " + String.format("%.1f", 1.0 / realtimeFactor) + "x speedup for realtime.");
			} else {
				log("OK: Faster than realtime (" + String.format("%.1f", realtimeFactor) + "x headroom)");
			}

			// Release notes
			synth.noteOff(60);
			synth.noteOff(64);
			synth.noteOff(67);
			synth.noteOff(72);

			bufferLine.destroy();

		} catch (InterruptedException e) {
			log("Test interrupted: " + e.getMessage());
		} finally {
			// Save profile
			String profilePath = RESULTS_DIR + "/synthSchedulerPerf.xml";
			profile.save(profilePath);
			log("\nProfile saved to: " + profilePath);
			log("Analyze with: mcp__ar-profile-analyzer__load_profile path: \"audio/" + profilePath + "\"");
		}
	}

	/**
	 * Profiles the TemporalRunner execution pattern which is used internally
	 * by BufferedOutputScheduler. This isolates the runner overhead.
	 */
	@Test
	public void profileTemporalRunner() throws IOException {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		ensureResultsDirectory();

		log("=== TemporalRunner Performance Profile ===");

		// Create profiler
		OperationProfileNode profile = new OperationProfileNode("PolyphonicSynthesizer TemporalRunner");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			// Create synthesizer
			PolyphonicSynthesizer synth = createConfiguredSynth();

			// Create CellList
			CellList cells = new CellList();
			cells.addRoot(synth);

			// Create output buffer - this will be the buffer size per tick
			int framesPerTick = 512;
			int numTicks = 100;
			int totalFrames = framesPerTick * numTicks;
			PackedCollection destination = new PackedCollection(framesPerTick);

			log("Frames per tick: " + framesPerTick);
			log("Number of ticks: " + numTicks);
			log("Total frames: " + totalFrames);

			// Create TemporalRunner with the correct iteration count
			// buffer() creates a runner with iterations = destination size
			TemporalRunner runner = buffer(cells, p(destination));

			// Trigger notes
			log("\nTriggering chord...");
			synth.noteOn(60, 0.8);
			synth.noteOn(64, 0.7);

			// Compile runner operation - get() includes setup + one buffer worth of ticks
			log("\nCompiling runner operation...");
			long compileStart = System.currentTimeMillis();
			Runnable setupAndFirstTick = runner.get();
			Runnable continueTick = runner.getContinue();
			long compileEnd = System.currentTimeMillis();
			log("Compilation time: " + (compileEnd - compileStart) + " ms");

			// Run setup + first tick
			log("\nRunning setup...");
			setupAndFirstTick.run();

			// Run profiled execution for remaining ticks
			log("Running " + (numTicks - 1) + " more ticks...");
			long runStart = System.nanoTime();
			for (int i = 1; i < numTicks; i++) {
				continueTick.run();
			}
			long runEnd = System.nanoTime();
			double runTimeMs = (runEnd - runStart) / 1_000_000.0;

			// Calculate metrics (only measuring the loop portion, not setup)
			double audioTimeMs = (double) (totalFrames - framesPerTick) / SAMPLE_RATE * 1000;
			double realtimeFactor = audioTimeMs / runTimeMs;

			log("\n=== TemporalRunner Results ===");
			log("Run time: " + String.format("%.2f", runTimeMs) + " ms");
			log("Audio time: " + String.format("%.2f", audioTimeMs) + " ms");
			log("Realtime factor: " + String.format("%.2f", realtimeFactor) + "x");

			if (realtimeFactor < 1.0) {
				log("WARNING: Slower than realtime!");
			} else {
				log("OK: Faster than realtime (" + String.format("%.1f", realtimeFactor) + "x headroom)");
			}

			// Release notes
			synth.noteOff(60);
			synth.noteOff(64);

			destination.destroy();

		} finally {
			// Save profile
			String profilePath = RESULTS_DIR + "/synthRunnerPerf.xml";
			profile.save(profilePath);
			log("\nProfile saved to: " + profilePath);
		}
	}

	/**
	 * Profiles with varying voice counts to understand scaling behavior.
	 */
	@Test
	public void profileVoiceScaling() throws IOException {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		ensureResultsDirectory();

		log("=== Voice Scaling Performance Profile ===");

		int[] voiceCounts = {1, 2, 4, 8};
		int ticksPerTest = 500;

		for (int voiceCount : voiceCounts) {
			log("\n--- Testing with " + voiceCount + " voices ---");

			// Create profiler for this voice count
			OperationProfileNode profile = new OperationProfileNode("Synth " + voiceCount + " Voices");
			Hardware.getLocalHardware().assignProfile(profile);

			try {
				// Create synth with specified voice count
				PolyphonicSynthesizer synth = new PolyphonicSynthesizer(voiceCount);
				configureSynth(synth);

				// Create output
				PackedCollection destination = new PackedCollection(1024);
				org.almostrealism.audio.WaveOutput waveOutput = new org.almostrealism.audio.WaveOutput(p(destination));
				waveOutput.setCircular(true);
				synth.setReceptor(waveOutput.getWriterCell(0));

				// Compile
				Runnable setup = synth.setup().get();
				Runnable tick = synth.tick().get();

				// Setup
				setup.run();

				// Trigger all voices
				for (int v = 0; v < voiceCount; v++) {
					synth.noteOn(60 + v * 4, 0.8);
				}

				// Warm up
				for (int i = 0; i < 50; i++) {
					tick.run();
				}

				// Profile
				long start = System.nanoTime();
				for (int i = 0; i < ticksPerTest; i++) {
					tick.run();
				}
				long end = System.nanoTime();

				double totalMs = (end - start) / 1_000_000.0;
				double avgTickUs = (end - start) / 1000.0 / ticksPerTest;
				double audioMs = ticksPerTest * 1000.0 / SAMPLE_RATE;
				double realtimeFactor = audioMs / totalMs;

				log("Total time: " + String.format("%.2f", totalMs) + " ms");
				log("Avg tick: " + String.format("%.2f", avgTickUs) + " us");
				log("Realtime factor: " + String.format("%.2f", realtimeFactor) + "x");

				// Release
				for (int v = 0; v < voiceCount; v++) {
					synth.noteOff(60 + v * 4);
				}

				destination.destroy();

			} finally {
				// Save profile for this voice count
				String profilePath = RESULTS_DIR + "/synthVoices" + voiceCount + ".xml";
				profile.save(profilePath);
			}
		}

		log("\n=== Voice Scaling Complete ===");
		log("Profiles saved to " + RESULTS_DIR + "/synthVoices*.xml");
	}

	/**
	 * Creates a synthesizer with the standard test configuration.
	 */
	private PolyphonicSynthesizer createConfiguredSynth() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(VOICE_COUNT);
		configureSynth(synth);
		return synth;
	}

	/**
	 * Applies standard configuration to a synthesizer.
	 */
	private void configureSynth(PolyphonicSynthesizer synth) {
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.01, 0.1, 0.7, 0.3);
		synth.setLowPassFilter(2000.0, 1.5);
	}

	/**
	 * Ensures the results directory exists.
	 */
	private void ensureResultsDirectory() {
		File dir = new File(RESULTS_DIR);
		if (!dir.exists()) {
			dir.mkdirs();
		}
	}
}
