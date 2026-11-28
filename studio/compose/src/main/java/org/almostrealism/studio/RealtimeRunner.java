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

package org.almostrealism.studio;

import org.almostrealism.heredity.TemporalCellular;

/**
 * A real-time scene runner together with the control handle a live consumer needs.
 *
 * <p>{@link TemporalCellular#reset()} rewinds every cursor and the global clock and clears
 * the mixdown state (reverb tails, effects feedback), so it cannot be used to move playback
 * while a performance continues. This interface adds the operations a live consumer drives
 * between its own ticks:</p>
 *
 * <ul>
 *   <li>{@link #retarget(long)} moves the render position without touching mixdown state;</li>
 *   <li>{@link #slotReady()} and the progress probes report, without blocking, whether the
 *       next {@link #tick()} would have to wait on a render.</li>
 * </ul>
 *
 * <p>All methods except the probes must be called from the single thread that ticks the
 * runner, between ticks.</p>
 *
 * @see AudioSceneRealtimeRunner#create(org.almostrealism.studio.health.MultiChannelAudioOutput,
 *      java.util.List, int, RealtimeRunnerOptions)
 */
public interface RealtimeRunner extends TemporalCellular {

	/**
	 * Restarts rendering so that the next buffer the runner produces is the pattern position
	 * {@code startFrame} (wrapped into the runner's loop window). The render-ahead ring is
	 * emptied by a cooperative stop and refilled with a one-buffer prefill before this
	 * returns; the automation clock is moved to the same position. The mixdown state is not
	 * reset, so reverb and effects tails carry across the retarget.
	 *
	 * <p>Must be called from the ticking thread, between ticks. It must never be called from
	 * the render-ahead producer thread.</p>
	 *
	 * @param startFrame the pattern position of the next buffer to produce
	 */
	default void retarget(long startFrame) {
		retarget(startFrame, null);
	}

	/**
	 * Retargets as {@link #retarget(long)} does, running {@code whileStopped} after the
	 * render-ahead producer has stopped and before it restarts. No render is in progress
	 * while it runs, so it may change state the producer reads, such as assigning a new genome
	 * to the scene.
	 *
	 * @param startFrame   the pattern position of the next buffer to produce
	 * @param whileStopped run while the producer is stopped, or {@code null}
	 */
	void retarget(long startFrame, Runnable whileStopped);

	/**
	 * Returns {@code true} when the next {@link #tick()} has a rendered buffer to consume, so
	 * it will not block waiting for the producer. Never blocks.
	 */
	boolean slotReady();

	/** Returns the number of buffers rendered since the last (re)start. Never blocks. */
	long buffersRendered();

	/** Returns the number of buffers consumed since the last (re)start. Never blocks. */
	long buffersConsumed();

	/**
	 * Returns the render-ahead ring depth in buffers.
	 *
	 * <p>This is a property of the render-ahead ring, which only the PDSL path owns: it
	 * reports the configured depth (the per-runner
	 * {@link RealtimeRunnerOptions#renderAheadSlots(int) override} when positive, otherwise
	 * the global {@link AudioSceneRealtimeRunner#renderAheadSlots}). The CellList path has no
	 * ring — it renders inline within each tick — so it always reports {@code 1} and ignores
	 * any configured depth. A caller that needs the override to take effect must therefore use
	 * a channel selection the PDSL path supports (see
	 * {@link org.almostrealism.studio.arrange.MixdownManager#supportsPdslMixdown}) with the
	 * PDSL mixdown enabled; otherwise the depth is not applicable and this reports {@code 1}.</p>
	 */
	int renderAheadSlots();

	/**
	 * Returns {@code true} when the render-ahead ring holds as many rendered, unconsumed
	 * buffers as it can. Never blocks.
	 */
	default boolean isRingFull() {
		return buffersRendered() - buffersConsumed() >= renderAheadSlots();
	}

	/** Returns the position window that maps render cursor frames to pattern positions. */
	RealtimePositionWindow getWindow();
}
