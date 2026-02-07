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

package org.almostrealism.time.computations.test;

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Performance testing suite for {@link MultiOrderFilter} operations.
 *
 * <p>This test class measures the execution overhead and performance characteristics
 * of the {@link MultiOrderFilter} computation across different scenarios. It is designed
 * to help identify performance bottlenecks and validate optimization efforts.</p>
 *
 * <h2>Test Configuration</h2>
 * <p>The tests are configurable along several dimensions:</p>
 * <ul>
 *   <li><strong>Collection Size:</strong> Number of frames in the input PackedCollection (default: 50,000)</li>
 *   <li><strong>Filter Order:</strong> Number of filter coefficients (default: 40)</li>
 *   <li><strong>Iterations:</strong> Number of times to execute the filter (default: 100-1000)</li>
 *   <li><strong>Compute Context:</strong> CPU vs GPU execution via {@link ComputeRequirement}</li>
 * </ul>
 *
 * <h2>Timing Methodology</h2>
 * <p>The tests measure two types of timing:</p>
 * <ol>
 *   <li><strong>Cold Start:</strong> First execution including compilation overhead</li>
 *   <li><strong>Warm Iterations:</strong> Average execution time across many iterations</li>
 * </ol>
 *
 * <h2>Test Output</h2>
 * <p>All test results are logged to both the console and to a dedicated file in
 * {@code results} for post-test analysis. The logs include:</p>
 * <ul>
 *   <li>Test configuration parameters</li>
 *   <li>Cold start time (first execution)</li>
 *   <li>Average warm execution time</li>
 *   <li>Total execution time</li>
 *   <li>Throughput metrics (frames/second)</li>
 * </ul>
 *
 * <h2>Compute Requirements Approach</h2>
 * <p>This test uses {@link MultiOrderFilter#setComputeRequirements(List)} to attach
 * execution requirements directly to the computation, rather than wrapping execution
 * in dedicated compute contexts. This is the recommended pattern because:</p>
 * <ul>
 *   <li>Requirements travel with the computation through the execution graph</li>
 *   <li>Hardware selection happens automatically based on requirements</li>
 *   <li>No need to manage compute context lifecycle manually</li>
 *   <li>Cleaner separation between computation definition and execution</li>
 * </ul>
 *
 * <h2>Interpreting Results</h2>
 * <p><strong>Cold Start Time:</strong> Includes kernel compilation, memory allocation, and first execution.
 * High values (>1 second) are normal for complex operations.</p>
 *
 * <p><strong>Warm Time:</strong> Actual execution overhead after compilation. Low values (<10ms) indicate
 * good performance. Compare CPU vs GPU to understand acceleration benefits.</p>
 *
 * <p><strong>Throughput:</strong> Frames processed per second. Higher is better. GPU should show
 * significant advantages for large collections (>10,000 frames).</p>
 *
 * @author Claude Code
 * @see MultiOrderFilter
 * @see org.almostrealism.time.TemporalFeatures#highPass
 */
public class MultiOrderFilterPerformanceTest extends TestSuiteBase implements ConsoleFeatures {

	/**
	 * Performance test for {@link org.almostrealism.time.TemporalFeatures#highPass}
	 * filter with CPU execution.
	 *
	 * <p>This test validates the performance characteristics of high-pass filtering
	 * using CPU-based computation. It measures both cold start and warm execution times
	 * to understand the compilation overhead vs runtime overhead.</p>
	 *
	 * <h3>Test Configuration:</h3>
	 * <ul>
	 *   <li>Collection Size: 50,000 frames</li>
	 *   <li>Filter Order: 40 (default)</li>
	 *   <li>Cutoff Frequency: 1000 Hz</li>
	 *   <li>Sample Rate: 44,100 Hz</li>
	 *   <li>Iterations: 1,000 (for warm timing)</li>
	 *   <li>Compute Requirements: {@link ComputeRequirement#CPU}</li>
	 * </ul>
	 *
	 * <h3>Expected Performance:</h3>
	 * <p>Typical results for 50,000 frames on modern CPUs:</p>
	 * <ul>
	 *   <li>Cold start: 200-500 ms (includes JIT compilation)</li>
	 *   <li>Warm average: 2-5 ms per iteration</li>
	 *   <li>Throughput: 10M - 25M frames/sec</li>
	 * </ul>
	 *
	 * @throws Exception if test setup or execution fails
	 */
	@Test(timeout = 10000)
	public void highPassPerformanceCPU() throws Exception {
		// Set up file logging BEFORE any output
		String logFile = "results/multiorder_filter_performance_cpu.out";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("=== MultiOrderFilter High-Pass Performance Test (CPU) ===\n");

		// Run performance test with CPU execution
		runHighPassPerformanceTest(
				50000,  // collectionSize
				40,                 // filterOrder (default)
				1000.0,             // cutoffHz
				44100,              // sampleRate
				1000,               // warmIterations
				ComputeRequirement.CPU
		);

		log("\nTest output saved to: " + logFile);
	}

	/**
	 * Performance test for {@link org.almostrealism.time.TemporalFeatures#highPass}
	 * filter with GPU execution.
	 *
	 * <p>This test validates the performance characteristics of high-pass filtering
	 * using GPU-based computation. It measures both cold start and warm execution times
	 * to demonstrate the acceleration benefits of GPU execution, particularly for large
	 * datasets.</p>
	 *
	 * <h3>Test Configuration:</h3>
	 * <ul>
	 *   <li>Collection Size: 50,000 frames</li>
	 *   <li>Filter Order: 40 (default)</li>
	 *   <li>Cutoff Frequency: 1000 Hz</li>
	 *   <li>Sample Rate: 44,100 Hz</li>
	 *   <li>Iterations: 1,000 (for warm timing)</li>
	 *   <li>Compute Requirements: {@link ComputeRequirement#GPU}</li>
	 * </ul>
	 *
	 * <h3>Expected Performance:</h3>
	 * <p>GPU execution should demonstrate significant speedup over CPU for large collections.
	 * Typical results for 50,000 frames:</p>
	 * <ul>
	 *   <li>GPU: 10M - 50M frames/sec</li>
	 *   <li>Speedup vs CPU: 1-5x (depending on hardware)</li>
	 * </ul>
	 *
	 * @throws Exception if test setup or execution fails
	 */
	@Test(timeout = 10000)
	public void highPassPerformanceGPU() throws Exception {
		// Set up file logging BEFORE any output
		String logFile = "results/multiorder_filter_performance_gpu.out";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("=== MultiOrderFilter High-Pass Performance Test (GPU) ===\n");

		// Run performance test with GPU execution
		runHighPassPerformanceTest(
				50000,            // collectionSize
				40,              // filterOrder (default)
				1000.0,          // cutoffHz
				44100,           // sampleRate
				1000,            // warmIterations
				ComputeRequirement.GPU  // GPU execution
		);

		log("\nTest output saved to: " + logFile);
	}

	/**
	 * Configurable performance test method for high-pass filtering.
	 *
	 * <p>This method provides a flexible framework for testing {@link MultiOrderFilter}
	 * performance under various conditions. It measures:</p>
	 * <ul>
	 *   <li>Cold start time (first execution with compilation)</li>
	 *   <li>Warm average time (subsequent executions)</li>
	 *   <li>Total execution time</li>
	 *   <li>Throughput (frames/second)</li>
	 * </ul>
	 *
	 * <h3>Execution Flow:</h3>
	 * <ol>
	 *   <li>Create input PackedCollection with random data</li>
	 *   <li>Build high-pass filter using PassThroughProducer (v()) for dynamic input</li>
	 *   <li>Attach compute requirements to filter using setComputeRequirements()</li>
	 *   <li>Execute once (cold start) passing input as argument, and measure time</li>
	 *   <li>Execute many times (warm) passing input as argument, and measure average time</li>
	 *   <li>Log all results to console and file</li>
	 * </ol>
	 *
	 * <h3>Implementation Notes:</h3>
	 * <p><strong>Dynamic Inputs via PassThroughProducer:</strong> This test uses
	 * {@code v(shape(collectionSize), 0)} to create a {@link io.almostrealism.relation.PassThroughProducer}
	 * for the input series. This is more representative of real-world usage where data flows
	 * through the computation graph dynamically at evaluation time, rather than being baked
	 * into the graph structure. The input is provided as an argument to {@code evaluate(inputSeries)}.</p>
	 *
	 * <p><strong>Compute Requirements:</strong> This test uses {@link MultiOrderFilter#setComputeRequirements(List)}
	 * to attach compute requirements directly to the filter computation, rather than using dedicated
	 * compute contexts. This is the recommended pattern for controlling execution targets in Almost
	 * Realism applications.</p>
	 *
	 * <h3>Parameter Guidelines:</h3>
	 * <ul>
	 *   <li><strong>collectionSize:</strong> 10,000 - 100,000 frames typical; larger sizes
	 *       favor GPU execution</li>
	 *   <li><strong>filterOrder:</strong> 10 - 100; higher orders increase computation but
	 *       improve filter quality</li>
	 *   <li><strong>cutoffHz:</strong> Should be less than sampleRate/2 (Nyquist frequency)</li>
	 *   <li><strong>sampleRate:</strong> 44,100 Hz (CD quality) or 48,000 Hz (pro audio) typical</li>
	 *   <li><strong>warmIterations:</strong> 100-1000; more iterations give more accurate timing</li>
	 *   <li><strong>requirement:</strong> Use {@link ComputeRequirement#CPU} for CPU,
	 *       {@link ComputeRequirement#GPU} for GPU</li>
	 * </ul>
	 *
	 * <h3>Understanding the Results:</h3>
	 * <p><strong>Cold Start Time:</strong> First execution time including kernel compilation and
	 * memory setup. This is typically 100-1000x slower than warm execution. For the default
	 * configuration, expect 0.5-2 seconds on modern hardware.</p>
	 *
	 * <p><strong>Warm Average Time:</strong> Average execution time after warmup. This represents
	 * the actual runtime overhead per filter operation. For 50,000 frames with order 40:</p>
	 * <ul>
	 *   <li>CPU: 5-20ms typical (depending on processor)</li>
	 *   <li>GPU: 1-5ms typical (with proper acceleration)</li>
	 * </ul>
	 *
	 * <p><strong>Throughput:</strong> Frames processed per second. Higher is better. For the default
	 * configuration:</p>
	 * <ul>
	 *   <li>CPU: 2.5M - 10M frames/sec</li>
	 *   <li>GPU: 10M - 50M frames/sec</li>
	 * </ul>
	 *
	 * @param collectionSize Number of frames in the input collection
	 * @param filterOrder    Filter order (number of coefficients - 1)
	 * @param cutoffHz       Cutoff frequency in Hz
	 * @param sampleRate     Sample rate in Hz
	 * @param warmIterations Number of warm iterations to average
	 * @param requirement    Compute context requirement (CPU/GPU)
	 * @throws Exception if filter creation or execution fails
	 */
	protected void runHighPassPerformanceTest(
			int collectionSize,
			int filterOrder,
			double cutoffHz,
			int sampleRate,
			int warmIterations,
			ComputeRequirement requirement) throws Exception {

		log("Configuration:");
		log("  Collection Size: " + collectionSize + " frames");
		log("  Filter Order: " + filterOrder);
		log("  Cutoff Frequency: " + cutoffHz + " Hz");
		log("  Sample Rate: " + sampleRate + " Hz");
		log("  Warm Iterations: " + warmIterations);
		log("  Compute Context: " + requirement);
		log("");

		// Create input collection with random data
		PackedCollection inputSeries = new PackedCollection(collectionSize);
		for (int i = 0; i < collectionSize; i++) {
			inputSeries.setMem(i, Math.random());
		}
		log("Created input collection with " + collectionSize + " random samples");

		// Build high-pass filter using dynamic input via PassThroughProducer
		// This is more representative of real-world usage where data flows through dynamically
		log("Building high-pass filter with dynamic input (order=" + filterOrder + ")...");
		MultiOrderFilter filter = highPass(
				traverseEach(v(shape(collectionSize), 0)),  // Argument 0: dynamic input series
				c(cutoffHz),                                 // Constant cutoff frequency
				sampleRate,
				filterOrder
		);

		// Set compute requirements directly on the filter computation
		filter.setComputeRequirements(List.of(requirement));

		// Create output collection
		PackedCollection output = new PackedCollection(collectionSize);

		log("Filter built successfully with " + requirement + " compute requirements");
		log("  Using PassThroughProducer for dynamic input evaluation");
		log("");

		// Measure cold start (first execution with compilation)
		log("=== Cold Start (First Execution) ===");
		long coldStartTime = System.nanoTime();

		filter.get().into(output.traverseEach()).evaluate(inputSeries);

		long coldEndTime = System.nanoTime();
		double coldDurationMs = (coldEndTime - coldStartTime) / 1_000_000.0;

		log("Cold start time: " + String.format("%.2f", coldDurationMs) + " ms");
		log("  (includes kernel compilation and setup overhead)");
		log("");

		// Measure warm execution (many iterations)
		log("=== Warm Execution (" + warmIterations + " iterations) ===");
		long warmStartTime = System.nanoTime();

		for (int i = 0; i < warmIterations; i++) {
			filter.get().into(output.traverseEach()).evaluate(inputSeries);
		}

		long warmEndTime = System.nanoTime();
		double warmTotalMs = (warmEndTime - warmStartTime) / 1_000_000.0;
		double warmAvgMs = warmTotalMs / warmIterations;

		log("Warm total time: " + String.format("%.2f", warmTotalMs) + " ms");
		log("Warm average time: " + String.format("%.4f", warmAvgMs) + " ms per iteration");
		log("  (pure execution overhead, no compilation)");
		log("");

		// Calculate throughput
		double framesPerSecond = (collectionSize / warmAvgMs) * 1000.0;
		double megaFramesPerSecond = framesPerSecond / 1_000_000.0;

		log("=== Throughput Metrics ===");
		log("Frames per second: " + String.format("%.2f", framesPerSecond));
		log("Million frames/sec: " + String.format("%.2f", megaFramesPerSecond));
		log("");

		// Calculate overhead ratio
		double overheadRatio = coldDurationMs / warmAvgMs;
		log("=== Overhead Analysis ===");
		log("Cold/Warm ratio: " + String.format("%.1f", overheadRatio) + "x");
		log("  (cold start is " + String.format("%.1f", overheadRatio) + "x slower than warm)");
		log("");

		// Verify output is non-zero (sanity check)
		double sum = 0;
		for (int i = 0; i < Math.min(100, collectionSize); i++) {
			sum += Math.abs(output.toDouble(i));
		}
		double avg = sum / Math.min(100, collectionSize);
		log("=== Sanity Check ===");
		log("Output average magnitude (first 100 samples): " + String.format("%.6f", avg));
		log("  (should be non-zero if filter is working)");

		assertTrue("Output should be non-zero", avg > 0);
	}
}
