/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.filter.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.filter.MultiOrderFilterEnvelopeProcessor;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Performance test for {@link MultiOrderFilterEnvelopeProcessor} that replicates
 * real-world usage patterns by loading histogram data from actual runs.
 * <p>
 * The test supports a "distractor computation" feature that simulates pipeline state
 * switching by randomly executing a separate multiply operation between filter calls.
 * This better models real-world performance where the GPU/CPU is switching between
 * different compute kernels. The distractor probability can be configured via
 * {@link #distractorProbability}.
 * </p>
 */
public class MultiOrderFilterEnvelopeProcessorPerformanceTest implements TestFeatures {
	public static final int SAMPLE_RATE = OutputLine.sampleRate;
	public static final double MAX_SECONDS = 90.0;

	public static boolean enableProfile = true;

	/** Size of distractor computation buffers (frames). */
	public static final int DISTRACTOR_SIZE = 50000;

	/** Probability of running distractor computation between filter calls (0.0 to 1.0). */
	public static double distractorProbability = 0.1;

	/**
	 * Loads histogram data from results/filter_dist.csv and runs the processor
	 * with the same distribution of input sizes to measure realistic performance.
	 * <p>
	 * This is a full-scale test that replicates all 400k+ calls from the histogram.
	 * Use {@link #scaled()} for a faster test.
	 * </p>
	 */
	@Test
	public void standard() throws IOException {
		runDistribution("filterEnv", 1.0, 0.0);
	}

	/**
	 * Runs the scaled test with 25% distractor probability.
	 */
	@Test
	public void highDistractor() throws IOException {
		runDistribution("filterEnvHighDistractor", 1.0, 0.9);
	}

	/**
	 * Runs a scaled-down version of the realistic distribution test (10% of calls).
	 * This is faster while still maintaining the distribution shape.
	 */
	@Test
	public void scaled() throws IOException {
		runDistribution("filterEnvScaled", 0.1, 0.0);
	}

	/**
	 * Runs the scaled test with 90% distractor probability.
	 */
	@Test
	public void scaledHighDistractor() throws IOException {
		runDistribution("filterEnvScaledHighDistractor", 0.1, 0.9);
	}

	/**
	 * Helper method that runs realistic distribution with profiling.
	 */
	protected void runDistribution(String name, double scaleFactor, double distractorProbability) throws IOException {
		OperationProfileNode profile = enableProfile ?
				new OperationProfileNode("MultiOrderFilterEnvelopeProcessorPerformanceTest") : null;
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			runDistribution(scaleFactor, distractorProbability);
		} finally {
			if (profile != null) {
				profile.save("results/" + name + ".xml");
			}
		}
	}

	/**
	 * Runs the realistic distribution test with a configurable scale factor.
	 *
	 * @param scaleFactor  Fraction of calls to process (0.0 to 1.0)
	 */
	protected void runDistribution(double scaleFactor, double distractorProbability) throws IOException {
		// Try multiple possible paths for the histogram file
		File histogramFile = new File("audio/results/filter_dist.csv");
		if (!histogramFile.exists()) {
			histogramFile = new File("results/filter_dist.csv");
		}
		if (!histogramFile.exists()) {
			histogramFile = new File("../audio/results/filter_dist.csv");
		}

		if (!histogramFile.exists()) {
			log("Histogram file not found.");
			log("Tried paths:");
			log("  - audio/results/filter_dist.csv");
			log("  - results/filter_dist.csv");
			log("  - ../audio/results/filter_dist.csv");
			log("Skipping performance test");
			return;
		}

		log("Using histogram file: " + histogramFile.getAbsolutePath());

		// Create processor
		MultiOrderFilterEnvelopeProcessor processor =
			new MultiOrderFilterEnvelopeProcessor(SAMPLE_RATE, MAX_SECONDS);

		// Configure ADSR parameters (typical values)
		processor.setDuration(5.0);
		processor.setAttack(0.5);
		processor.setDecay(1.0);
		processor.setSustain(0.7);
		processor.setRelease(2.0);

		// Load the histogram to get the distribution
		processor.loadHistogram(histogramFile);
		long[] histogram = processor.getHistogram();

		// Calculate total number of calls
		long totalCalls = 0;
		for (long count : histogram) {
			totalCalls += count;
		}

		log("Loaded histogram with " + totalCalls + " total calls");
		log("Scale factor: " + (scaleFactor * 100) + "%");

		// Generate input sizes matching the distribution
		List<Integer> inputSizes = generateInputSizes(histogram, scaleFactor);
		log("Generated " + inputSizes.size() + " test inputs");

		// Create distractor runner to simulate pipeline state switching
		DistractionRunner distractor = null;
		int distractionsPerOp = 0;
		if (distractorProbability > 0.0) {
			distractor = new DistractionRunner(DISTRACTOR_SIZE, distractorProbability);
			distractor.initialize();

			// Add diverse operation types to maximize pipeline state diversity
			distractor.addMultiply();
			distractor.addAdd();
			distractor.addSubtract();
			distractor.addDivide();
			distractor.addSum();
			distractor.addExp();
			distractor.addPow();
			distractor.addSqrt();
			distractor.addAbs();
			distractor.addMin();
			distractor.addMax();
			distractor.addZeros();

			distractionsPerOp = distractor.getDistractionsPerOperation();
			long totalDistractorOps = (long) inputSizes.size() * distractionsPerOp;
			long totalOps = inputSizes.size() + totalDistractorOps;

			log("Distractor enabled with " + distractor.getOperationCount() + " operation types");
			log("Distractor probability: " + (distractorProbability * 100) + "%");
			log("Distractions per filter operation: " + distractionsPerOp);
			log("Total operations: " + totalOps + " (" + inputSizes.size() +
				" filter + " + totalDistractorOps + " distractor)");
		}

		// Warm-up phase
		log("Warming up...");
		warmUp(processor, 100);

		// Run performance test
		log("Running performance test...");
		long startTime = System.nanoTime();
		long totalFramesProcessed = 0;

		for (int inputSize : inputSizes) {
			// Execute N distractor operations to flood the pipeline before the target operation
			if (distractor != null && distractionsPerOp > 0) {
				distractor.executeMultiple(distractionsPerOp);
			}

			PackedCollection input = new PackedCollection(inputSize).randnFill();
			PackedCollection output = new PackedCollection(inputSize).randnFill();
			processor.process(input, output);

			totalFramesProcessed += inputSize;

			input.destroy();
			output.destroy();
		}

		// Cleanup distractor resources
		if (distractor != null) {
			distractor.destroy();
		}

		long endTime = System.nanoTime();
		double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;

		// Report results
		log("\n=== Performance Results ===");
		log("Total calls: " + inputSizes.size());
		log("Total frames processed: " + totalFramesProcessed);
		if (distractor != null && distractor.getExecutionCount() > 0) {
			log("Distractor executions: " + distractor.getExecutionCount() +
				" (" + String.format("%.1f", (distractor.getExecutionCount() * 100.0) / inputSizes.size()) + "%)");
			log("Distractor operation types: " + distractor.getOperationCount());
		}
		log("Total time: " + String.format("%.3f", elapsedSeconds) + " seconds");
		log("Average time per call: " +
			String.format("%.3f", (elapsedSeconds / inputSizes.size()) * 1000) + " ms");
		log("Throughput: " +
			String.format("%.2f", totalFramesProcessed / elapsedSeconds) + " frames/sec");
		log("Throughput: " +
			String.format("%.2f", (totalFramesProcessed / SAMPLE_RATE) / elapsedSeconds) + "x realtime");

		processor.destroy();
	}

	/**
	 * Generates a list of input sizes matching the histogram distribution.
	 *
	 * @param histogram    The histogram bin counts
	 * @param scaleFactor  Fraction of calls to generate (0.0 to 1.0)
	 * @return List of input frame sizes to use for testing
	 */
	private List<Integer> generateInputSizes(long[] histogram, double scaleFactor) {
		List<Integer> sizes = new ArrayList<>();
		Random random = new Random(42); // Fixed seed for reproducibility

		for (int bin = 0; bin < histogram.length; bin++) {
			long count = histogram[bin];
			if (count == 0) continue;

			// Apply scale factor to count
			long scaledCount = Math.max(1, (long) (count * scaleFactor));

			int minFrames = MultiOrderFilterEnvelopeProcessor.HISTOGRAM_MIN_FRAMES +
				(bin * ((MultiOrderFilterEnvelopeProcessor.HISTOGRAM_MAX_FRAMES -
					MultiOrderFilterEnvelopeProcessor.HISTOGRAM_MIN_FRAMES) /
					MultiOrderFilterEnvelopeProcessor.HISTOGRAM_BINS));
			int maxFrames = minFrames +
				((MultiOrderFilterEnvelopeProcessor.HISTOGRAM_MAX_FRAMES -
					MultiOrderFilterEnvelopeProcessor.HISTOGRAM_MIN_FRAMES) /
					MultiOrderFilterEnvelopeProcessor.HISTOGRAM_BINS) - 1;

			if (bin == histogram.length - 1) {
				maxFrames = MultiOrderFilterEnvelopeProcessor.HISTOGRAM_MAX_FRAMES;
			}

			// Generate 'scaledCount' random sizes within this bin's range
			for (int i = 0; i < scaledCount; i++) {
				int size = minFrames + random.nextInt(maxFrames - minFrames + 1);
				sizes.add(size);
			}
		}

		// Shuffle to avoid sequential processing of same-sized inputs
		Collections.shuffle(sizes, random);

		return sizes;
	}

	/**
	 * Warm-up phase to ensure JIT compilation and cache warming.
	 *
	 * @param processor  The processor to warm up
	 * @param iterations Number of warm-up iterations
	 */
	private void warmUp(MultiOrderFilterEnvelopeProcessor processor, int iterations) {
		Random random = new Random(123);

		for (int i = 0; i < iterations; i++) {
			int size = 50000 + random.nextInt(100000);
			PackedCollection input = new PackedCollection(size);
			PackedCollection output = new PackedCollection(size);

			processor.process(input, output);

			input.destroy();
			output.destroy();
		}
	}

	/**
	 * Simple performance test with a single input size to establish baseline.
	 */
	@Test
	public void baseline() {
		log("Running baseline performance test...");

		MultiOrderFilterEnvelopeProcessor processor =
			new MultiOrderFilterEnvelopeProcessor(SAMPLE_RATE, MAX_SECONDS);

		processor.setDuration(5.0);
		processor.setAttack(0.5);
		processor.setDecay(1.0);
		processor.setSustain(0.7);
		processor.setRelease(2.0);

		// Warm-up
		warmUp(processor, 50);

		// Test with a typical size
		int testSize = 132000; // ~3 seconds at 44100 Hz
		int iterations = 1000;

		log("Testing with " + iterations + " iterations of " + testSize + " frames");

		long startTime = System.nanoTime();

		for (int i = 0; i < iterations; i++) {
			PackedCollection input = new PackedCollection(testSize);
			PackedCollection output = new PackedCollection(testSize);

			processor.process(input, output);

			input.destroy();
			output.destroy();
		}

		long endTime = System.nanoTime();
		double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;

		log("\n=== Baseline Results ===");
		log("Total time: " + String.format("%.3f", elapsedSeconds) + " seconds");
		log("Average time per call: " +
			String.format("%.3f", (elapsedSeconds / iterations) * 1000) + " ms");
		log("Throughput: " +
			String.format("%.2f", (testSize * iterations) / elapsedSeconds) + " frames/sec");

		processor.destroy();
	}
}
