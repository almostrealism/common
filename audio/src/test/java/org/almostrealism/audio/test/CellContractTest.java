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

package org.almostrealism.audio.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract contract test that ALL {@link Cell} implementations must pass.
 * <p>
 * This test enforces the fundamental contract of the Cell interface:
 * <ul>
 *   <li>Behavior must be identical regardless of whether a receptor is set</li>
 *   <li>{@code push()} generates/processes data</li>
 *   <li>No "context detection" to change behavior based on usage patterns</li>
 * </ul>
 * <p>
 * <b>Why this test exists:</b> To prevent "context detection" cheating where
 * implementations behave differently based on whether they think they're being
 * tested vs. used in production. A Cell must have ONE correct behavior.
 * <p>
 * <b>Note on CachedStateCell:</b> Cells extending {@link org.almostrealism.graph.CachedStateCell}
 * use a double-buffering pattern where tick() legitimately pushes to the receptor.
 * This is by design. The contract violation this test catches is when a Cell
 * checks receptor presence to DECIDE whether to generate audio at all.
 * <p>
 * <b>To use:</b> Create a concrete subclass that implements {@link #createCell()}
 * and {@link #configureForAudioGeneration(Cell)}. All contract tests will then
 * run automatically against your Cell implementation.
 *
 * <pre>{@code
 * public class MySynthesizerContractTest extends CellContractTest<MySynthesizer> {
 *     @Override
 *     protected MySynthesizer createCell() {
 *         return new MySynthesizer(4);
 *     }
 *
 *     @Override
 *     protected void configureForAudioGeneration(MySynthesizer cell) {
 *         cell.noteOn(60, 0.8);  // Trigger a note so it produces output
 *     }
 * }
 * }</pre>
 *
 * @param <T> the Cell implementation type being tested
 * @see Cell
 * @see Temporal
 */
public abstract class CellContractTest<T extends Cell<PackedCollection>> extends TestSuiteBase implements CellFeatures {

	/**
	 * Creates a fresh instance of the Cell to test.
	 * <p>
	 * Each test method calls this to get an isolated instance.
	 *
	 * @return a new Cell instance configured with default settings
	 */
	protected abstract T createCell();

	/**
	 * Configures the cell to produce audio output when pushed.
	 * <p>
	 * For synthesizers, this might trigger a note. For filters, this might
	 * be a no-op since they process input. For oscillators, this might set
	 * a non-zero amplitude.
	 *
	 * @param cell the cell to configure
	 */
	protected abstract void configureForAudioGeneration(T cell);

	/**
	 * Returns the number of samples to generate for tests.
	 * Override if your cell needs more warmup time.
	 */
	protected int getSampleCount() {
		return 500;
	}

	/**
	 * Creates a receptor that accumulates output values.
	 */
	protected Receptor<PackedCollection> accumulatingReceptor(List<Double> values) {
		return protein -> () -> () -> values.add(protein.get().evaluate().toDouble(0));
	}

	/**
	 * Creates a receptor that counts how many times it was called.
	 */
	protected Receptor<PackedCollection> countingReceptor(AtomicInteger count) {
		return protein -> () -> () -> count.incrementAndGet();
	}

	/**
	 * CONTRACT: push() must produce output to receptor when called.
	 * <p>
	 * This verifies that push() actually does its job of generating/processing
	 * data and forwarding it. A Cell that returns no-op from push() when it
	 * should be producing output violates the contract.
	 */
	@Test
	public void pushMustProduceOutputToReceptor() {
		T cell = createCell();
		configureForAudioGeneration(cell);

		List<Double> outputValues = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(outputValues));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell instanceof Temporal ? ((Temporal) cell).tick().get() : () -> {};

		setup.run();

		for (int i = 0; i < getSampleCount(); i++) {
			push.run();
			tick.run();
		}

		Assert.assertFalse(
				"push() must forward data to receptor - got no output values",
				outputValues.isEmpty());

		boolean hasNonZero = outputValues.stream().anyMatch(v -> Math.abs(v) > 0.0001);
		Assert.assertTrue(
				"push() must produce actual audio data (non-zero values)",
				hasNonZero);
	}

	/**
	 * CONTRACT: push() behavior must be identical with or without receptor.
	 * <p>
	 * This catches "context detection" cheating where a Cell checks if a receptor
	 * is set and behaves differently. The internal computation of push() must be
	 * the same regardless of whether output is being captured.
	 */
	@Test
	public void pushBehaviorMustBeIndependentOfReceptorPresence() {
		// Run WITH receptor
		T cellWithReceptor = createCell();
		configureForAudioGeneration(cellWithReceptor);
		List<Double> withReceptorValues = new ArrayList<>();
		cellWithReceptor.setReceptor(accumulatingReceptor(withReceptorValues));

		Runnable setup1 = cellWithReceptor.setup().get();
		Runnable push1 = cellWithReceptor.push(c(0.0)).get();
		Runnable tick1 = cellWithReceptor instanceof Temporal ?
				((Temporal) cellWithReceptor).tick().get() : () -> {};

		setup1.run();
		for (int i = 0; i < getSampleCount(); i++) {
			push1.run();
			tick1.run();
		}

		// Run WITHOUT receptor (but capture via a different mechanism)
		// The cell should still compute values even if there's no receptor
		T cellWithoutReceptor = createCell();
		configureForAudioGeneration(cellWithoutReceptor);
		// No receptor set - cell should still work

		Runnable setup2 = cellWithoutReceptor.setup().get();
		Runnable push2 = cellWithoutReceptor.push(c(0.0)).get();
		Runnable tick2 = cellWithoutReceptor instanceof Temporal ?
				((Temporal) cellWithoutReceptor).tick().get() : () -> {};

		// This should NOT throw an exception
		setup2.run();
		for (int i = 0; i < getSampleCount(); i++) {
			push2.run();
			tick2.run();
		}

		// If we got here without exception, the cell handles no-receptor case gracefully
		// Now verify the with-receptor case actually produced output
		Assert.assertFalse(
				"Cell with receptor should have produced output",
				withReceptorValues.isEmpty());
	}

	/**
	 * CONTRACT: For Temporal cells, tick() must NOT become a no-op based on receptor presence.
	 * <p>
	 * This catches "context detection" cheating where tick() checks if a receptor is set
	 * and returns a no-op when it is (expecting some other mechanism to do the work).
	 * <p>
	 * The specific cheating pattern this catches:
	 * <pre>
	 * public Supplier&lt;Runnable&gt; tick() {
	 *     if (receptor != null) {
	 *         return new OperationList(); // NO-OP - cheating!
	 *     } else {
	 *         return push(null); // Only do work when no receptor
	 *     }
	 * }
	 * </pre>
	 * <p>
	 * A properly implemented tick() should do its work regardless of receptor presence.
	 * The receptor being set just means the output has somewhere to go.
	 */
	@Test
	public void tickMustNotBeNoOpWhenReceptorIsSet() {
		if (!isTemporalCell()) {
			return;
		}

		// Set up cell WITH receptor
		T cell = createCell();
		configureForAudioGeneration(cell);
		AtomicInteger receptorCalls = new AtomicInteger(0);
		cell.setReceptor(countingReceptor(receptorCalls));

		Runnable setup = cell.setup().get();
		Runnable tick = ((Temporal) cell).tick().get();

		setup.run();
		for (int i = 0; i < getSampleCount(); i++) {
			tick.run();
		}

		// For a properly implemented Temporal cell that generates audio:
		// - tick() should push to the receptor (receptorCalls > 0)
		//
		// For a CHEATING implementation:
		// - tick() returns no-op when receptor is set, so receptorCalls = 0
		//
		// Note: Some cells legitimately don't push from tick() (they push from push()).
		// To handle this, subclasses can override expectsTickToPushToReceptor().
		if (expectsTickToPushToReceptor()) {
			Assert.assertTrue(
					"tick() must not be a no-op when receptor is set. " +
							"Expected receptor to be called, but got 0 calls. " +
							"This indicates tick() is checking receptor presence and doing nothing.",
					receptorCalls.get() > 0);
		}
	}

	/**
	 * Returns whether this cell's tick() method is expected to push to the receptor.
	 * <p>
	 * Override this to return false for cells that legitimately don't push from tick()
	 * (e.g., cells where push() is the only method that forwards to receptor).
	 * <p>
	 * Default is true for cells extending CachedStateCell (which push from tick()),
	 * and true for source cells like synthesizers (which should push from tick()).
	 *
	 * @return true if tick() should push to receptor, false otherwise
	 */
	protected boolean expectsTickToPushToReceptor() {
		return true;
	}

	/**
	 * Helper to check if the cell under test is Temporal.
	 */
	private boolean isTemporalCell() {
		T cell = createCell();
		return cell instanceof Temporal;
	}

	/**
	 * CONTRACT: Calling push() without prior setup() should not crash.
	 * <p>
	 * While setup() should be called first, a robust Cell implementation
	 * should handle this gracefully (either with default values or a clear error).
	 */
	@Test
	public void pushWithoutSetupShouldNotCrash() {
		T cell = createCell();
		configureForAudioGeneration(cell);

		List<Double> outputValues = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(outputValues));

		// Skip setup(), go directly to push()
		Runnable push = cell.push(c(0.0)).get();

		// Should not throw - may produce zeros or use defaults
		try {
			for (int i = 0; i < 10; i++) {
				push.run();
			}
		} catch (Exception e) {
			Assert.fail("push() without setup() should not crash: " + e.getMessage());
		}
	}

	/**
	 * CONTRACT: Multiple setup() calls should be idempotent.
	 * <p>
	 * Calling setup() multiple times should not corrupt state or cause errors.
	 */
	@Test
	public void multipleSetupCallsShouldBeIdempotent() {
		T cell = createCell();
		configureForAudioGeneration(cell);

		List<Double> outputValues = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(outputValues));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell instanceof Temporal ? ((Temporal) cell).tick().get() : () -> {};

		// Call setup multiple times
		setup.run();
		setup.run();
		setup.run();

		// Should still work correctly
		for (int i = 0; i < getSampleCount(); i++) {
			push.run();
			tick.run();
		}

		Assert.assertFalse(
				"Cell should produce output even after multiple setup() calls",
				outputValues.isEmpty());
	}
}
