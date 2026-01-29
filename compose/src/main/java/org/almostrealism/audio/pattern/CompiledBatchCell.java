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

package org.almostrealism.audio.pattern;

import io.almostrealism.code.Computation;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalFeatures;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A batch cell that combines pattern rendering with a compiled loop for per-frame processing.
 *
 * <p>{@code CompiledBatchCell} implements the optimized real-time audio architecture where:</p>
 * <ul>
 *   <li>Pattern rendering happens once per buffer (non-compilable Java operation)</li>
 *   <li>Per-frame effects processing runs as a compiled Loop (N iterations in native code)</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>{@code
 * BATCH CELL TICK (called once per buffer):
 *   (1) RENDER - render N frames of pattern data (non-compilable, runs once)
 *   (2) LOOP(per-frame processing, N) - compiled Loop through effects chain (compilable)
 * }</pre>
 *
 * <p>The outer tick is an OperationList where child (1) is non-compilable and child (2)
 * is a compiled Loop. The Loop contains the per-frame effects processing that must be
 * a Computation.</p>
 *
 * <h2>Performance Benefit</h2>
 * <p>Unlike {@link BatchCell} which uses Java lambdas for per-sample iteration (44100
 * lambda invocations per second), this class:
 * <ul>
 *   <li>Calls tick() once per buffer (~43 calls/sec at 1024-sample buffers)</li>
 *   <li>Executes per-frame processing as a native for-loop in compiled code</li>
 * </ul>
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * int bufferSize = 1024;
 *
 * // Non-compilable: render patterns for this buffer
 * Supplier<Runnable> renderOp = () -> () -> {
 *     patterns.sum(ctx, channel, currentFrame, bufferSize).get().run();
 * };
 *
 * // Compilable: per-frame effects processing (must be Computation)
 * Supplier<Runnable> frameOp = cells.tick();  // CellList.tick() returns Computation
 *
 * CompiledBatchCell batchCell = new CompiledBatchCell(renderOp, frameOp, bufferSize,
 *     frame -> currentFrame = frame);
 *
 * // Call once per buffer - NOT per sample
 * batchCell.tick().get().run();  // Renders patterns + runs N-frame loop
 * }</pre>
 *
 * <h2>Loop Compilation</h2>
 * <p>The {@code frameOp} is wrapped using {@link TemporalFeatures#loop(Supplier, int)}.
 * If {@code frameOp} is a {@link Computation}, the loop compiles to a native for-loop.
 * If not, it falls back to a Java loop (which defeats the performance optimization).</p>
 *
 * @see BatchCell
 * @see org.almostrealism.hardware.computations.Loop
 * @see TemporalFeatures#loop(Supplier, int)
 *
 * @author Michael Murray
 */
public class CompiledBatchCell implements Temporal, Lifecycle, CodeFeatures {

	private final Supplier<Runnable> renderOp;
	private final Supplier<Runnable> frameOp;
	private final int batchSize;
	private final IntConsumer frameCallback;

	private int currentBatch;

	/**
	 * Creates a CompiledBatchCell without frame callback.
	 *
	 * @param renderOp The pattern rendering operation (non-compilable, runs once per tick)
	 * @param frameOp The per-frame effects processing (should be Computation for native loop)
	 * @param batchSize Number of frames per batch
	 */
	public CompiledBatchCell(Supplier<Runnable> renderOp, Supplier<Runnable> frameOp, int batchSize) {
		this(renderOp, frameOp, batchSize, null);
	}

	/**
	 * Creates a CompiledBatchCell with frame callback.
	 *
	 * @param renderOp The pattern rendering operation (non-compilable, runs once per tick)
	 * @param frameOp The per-frame effects processing (should be Computation for native loop)
	 * @param batchSize Number of frames per batch
	 * @param frameCallback Called with start frame position before each batch
	 */
	public CompiledBatchCell(Supplier<Runnable> renderOp, Supplier<Runnable> frameOp,
							 int batchSize, IntConsumer frameCallback) {
		this.renderOp = renderOp;
		this.frameOp = frameOp;
		this.batchSize = batchSize;
		this.frameCallback = frameCallback;
		this.currentBatch = 0;
	}

	/**
	 * Returns the batch size (frames per batch).
	 */
	public int getBatchSize() {
		return batchSize;
	}

	/**
	 * Returns the current batch number.
	 */
	public int getCurrentBatch() {
		return currentBatch;
	}

	/**
	 * Returns the start frame of the current batch.
	 */
	public int getCurrentFrame() {
		return currentBatch * batchSize;
	}

	/**
	 * Returns whether the frame operation is compilable.
	 *
	 * <p>If true, the per-frame processing will execute as a native for-loop.
	 * If false, it falls back to a Java loop.</p>
	 *
	 * @return true if frameOp is a Computation
	 */
	public boolean isFrameOpCompilable() {
		return frameOp instanceof Computation;
	}

	/**
	 * Performs one batch tick.
	 *
	 * <p>This method should be called once per buffer (not per sample). Each call:</p>
	 * <ol>
	 *   <li>Notifies the frame callback of the current position</li>
	 *   <li>Executes the render operation once (pattern rendering)</li>
	 *   <li>Executes the frame operation N times via a Loop</li>
	 *   <li>Advances to the next batch</li>
	 * </ol>
	 *
	 * @return OperationList containing [frameCallback, renderOp, loop(frameOp, batchSize)]
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("CompiledBatchCell Tick");

		// (1) Frame position callback (advance frame counter before rendering)
		tick.add(() -> () -> {
			int framePosition = currentBatch * batchSize;
			if (frameCallback != null) {
				frameCallback.accept(framePosition);
			}
		});

		// (2) Render patterns for this buffer (non-compilable, runs once)
		tick.add(renderOp);

		// (3) Per-frame effects processing as compiled Loop
		if (frameOp instanceof Computation) {
			// Use HardwareFeatures.loop to create a compiled Loop
			tick.add(HardwareFeatures.getInstance().loop((Computation<Void>) frameOp, batchSize));
		} else {
			// Fall back to Java loop iteration
			tick.add(javaLoop(frameOp, batchSize));
		}

		// (4) Advance batch counter (runs after loop completes)
		tick.add(() -> () -> currentBatch++);

		return tick;
	}

	/**
	 * Resets the batch counter.
	 */
	@Override
	public void reset() {
		currentBatch = 0;
	}

	/**
	 * Helper method to create a Java-based loop (fallback when frameOp is not a Computation).
	 */
	private Supplier<Runnable> javaLoop(Supplier<Runnable> op, int iterations) {
		return () -> {
			Runnable r = op.get();
			return () -> {
				for (int i = 0; i < iterations; i++) {
					r.run();
				}
			};
		};
	}
}
