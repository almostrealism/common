/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.optimize;

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.time.Temporal;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link Callable} wrapper that executes fitness evaluations for evolutionary algorithms.
 * <p>
 * {@code HealthCallable} encapsulates the execution of a {@link HealthComputation} within
 * a callable task, suitable for parallel evaluation using an {@link java.util.concurrent.ExecutorService}.
 * It handles the complete fitness evaluation lifecycle including:
 * </p>
 * <ul>
 *   <li>Setting up the target organism</li>
 *   <li>Computing the fitness score</li>
 *   <li>Recording statistics via {@link HealthScoring}</li>
 *   <li>Notifying listeners of results</li>
 *   <li>Cleanup after evaluation</li>
 *   <li>Error handling and reporting</li>
 * </ul>
 *
 * <h2>Compute Requirements</h2>
 * <p>
 * The class supports specifying compute requirements (e.g., GPU acceleration) via static
 * configuration. When requirements are set, fitness evaluations are executed within the
 * appropriate compute context.
 * </p>
 *
 * <h2>Memory Management</h2>
 * <p>
 * An optional {@link Heap} can be associated with the callable to manage memory allocation
 * during fitness evaluation. When set, the computation is wrapped to use heap-based allocation.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * HealthComputation<MyOrganism, HealthScore> computation = new MyHealthComputation();
 * HealthScoring scoring = new HealthScoring(100);
 *
 * HealthCallable<MyOrganism, HealthScore> callable = new HealthCallable<>(
 *     () -> population.enableGenome(index),  // Target supplier
 *     computation,                            // Fitness computation
 *     scoring,                                // Statistics aggregator
 *     score -> log("Score: " + score),        // Health listener
 *     population::disableGenome               // Cleanup action
 * );
 *
 * callable.setErrorListener(e -> handleError(e));
 * HealthScore result = callable.call();
 * }</pre>
 *
 * @param <T> the type of temporal entity being evaluated
 * @param <S> the type of health score produced
 *
 * @see HealthComputation
 * @see HealthScoring
 * @see PopulationOptimizer
 *
 * @author Michael Murray
 */
public class HealthCallable<T extends Temporal, S extends HealthScore> implements Callable<S>, CodeFeatures, ConsoleFeatures {
	/** Console for logging fitness evaluation output. */
	public static Console console = Console.root().child();

	/** Enables verbose logging of fitness evaluation progress. */
	public static boolean enableVerbose = false;

	/** Compute requirements for fitness evaluations (e.g., GPU acceleration). */
	public static ComputeRequirement computeRequirements[] = {};

	private HealthComputation<T, S> health;
	private Supplier<T> target;
	private Consumer<S> healthListener;
	private Consumer<Exception> errorListener;
	private HealthScoring scoring;
	private Runnable cleanup;

	private Heap heap;

	/**
	 * Creates a new health callable for fitness evaluation.
	 *
	 * @param target         supplier that provides the target organism for evaluation
	 * @param health         the health computation strategy
	 * @param scoring        the statistics aggregator for recording scores
	 * @param healthListener callback invoked with the computed score; may be null
	 * @param cleanup        action to run after evaluation completes; may be null
	 */
	public HealthCallable(Supplier<T> target, HealthComputation<T, S> health,
						  HealthScoring scoring, Consumer<S> healthListener,
						  Runnable cleanup) {
		this.health = health;
		this.target = target;
		this.scoring = scoring;
		this.healthListener = healthListener;
		this.cleanup = cleanup;
	}

	/**
	 * Returns the heap used for memory management during evaluation.
	 *
	 * @return the heap instance, or null if not set
	 */
	public Heap getHeap() { return heap; }

	/**
	 * Sets the heap for memory management during evaluation.
	 * <p>
	 * When a heap is set, the fitness computation is wrapped to use
	 * heap-based memory allocation.
	 * </p>
	 *
	 * @param heap the heap to use for memory allocation
	 */
	public void setHeap(Heap heap) { this.heap = heap; }

	/**
	 * Returns the error listener for handling evaluation failures.
	 *
	 * @return the error listener, or null if not set
	 */
	public Consumer<Exception> getErrorListener() { return errorListener; }

	/**
	 * Sets the error listener for handling evaluation failures.
	 * <p>
	 * If no listener is set, exceptions are printed to standard error.
	 * </p>
	 *
	 * @param errorListener callback for handling exceptions during evaluation
	 */
	public void setErrorListener(Consumer<Exception> errorListener) {
		this.errorListener = errorListener;
	}

	/**
	 * Executes the fitness evaluation and returns the computed score.
	 * <p>
	 * This method performs the following steps:
	 * </p>
	 * <ol>
	 *   <li>Sets the target organism on the health computation</li>
	 *   <li>Computes the fitness score</li>
	 *   <li>Records the score via the scoring aggregator</li>
	 *   <li>Notifies the health listener (if set)</li>
	 *   <li>Resets the health computation</li>
	 *   <li>Runs cleanup actions</li>
	 * </ol>
	 * <p>
	 * If compute requirements are configured, the evaluation runs within
	 * the appropriate compute context. If a heap is configured, memory
	 * allocation is managed through the heap.
	 * </p>
	 *
	 * @return the computed fitness score
	 * @throws Exception if the fitness evaluation fails
	 */
	@Override
	public S call() throws Exception {
		Callable<S> call = () -> {
			S healthResult;

			try {
				this.health.setTarget(target.get());
				if (enableVerbose) log("Running " + this.health.getClass().getSimpleName());
				healthResult = this.health.computeHealth();
				if (enableVerbose) log("Completed " + this.health.getClass().getSimpleName());
				scoring.pushScore(healthResult);

				if (healthListener != null) {
					healthListener.accept(healthResult);
				}
			} catch (Exception e) {
				if (getErrorListener() == null) {
					e.printStackTrace();
				} else {
					getErrorListener().accept(e);
				}

				throw e;
			} finally {
				this.health.reset();
				this.cleanup.run();
			}

			return healthResult;
		};

		if (heap != null) {
			call = heap.wrap(call);
		}

		if (enableVerbose) {
			log(computeRequirements == null ?
					"No compute requirements" : "Compute requirements: " + Arrays.toString(computeRequirements));
		}

		if (computeRequirements == null || computeRequirements.length <= 0) {
			return call.call();
		} else {
			return cc(call, computeRequirements);
		}
	}

	@Override
	public Console console() { return console; }

	/**
	 * Sets the compute requirements for all fitness evaluations.
	 * <p>
	 * Compute requirements specify hardware constraints such as GPU
	 * acceleration or specific compute capabilities needed for evaluation.
	 * </p>
	 *
	 * @param expectations the compute requirements to apply to all evaluations
	 */
	public static void setComputeRequirements(ComputeRequirement... expectations) {
		computeRequirements = expectations;
	}
}
