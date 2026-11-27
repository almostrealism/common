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

/**
 * Optional per-runner settings for
 * {@link AudioSceneRealtimeRunner#create(org.almostrealism.studio.health.MultiChannelAudioOutput,
 * java.util.List, int, RealtimeRunnerOptions)}.
 *
 * <p>Supplying options places the runner's render position in a {@link RealtimePositionWindow}
 * and lets the runner's automation clock follow that window: at the start of every tick the
 * clock is set to the pattern position of the buffer being mixed, so automation wraps with the
 * pattern instead of evolving across loop passes. Without options a runner behaves exactly as
 * before (positions follow the raw render cursor and the clock runs freely).</p>
 */
public class RealtimeRunnerOptions {

	/** Initial pattern position of the first buffer. */
	private long startFrame;

	/** Loop length in frames, or a non-positive value for no wrap. */
	private double loopFrames;

	/** Render-ahead ring depth for this runner, or a non-positive value for the global default. */
	private int renderAheadSlots;

	/** Creates options with no loop, a start at frame {@code 0} and the default ring depth. */
	public RealtimeRunnerOptions() { }

	/**
	 * Sets the pattern position of the first buffer.
	 *
	 * @param startFrame the start position in frames
	 * @return this options object
	 */
	public RealtimeRunnerOptions startFrame(long startFrame) {
		this.startFrame = startFrame;
		return this;
	}

	/**
	 * Sets the loop length.
	 *
	 * @param loopFrames loop length in frames (may be fractional), or non-positive for no wrap
	 * @return this options object
	 */
	public RealtimeRunnerOptions loopFrames(double loopFrames) {
		this.loopFrames = loopFrames;
		return this;
	}

	/**
	 * Overrides the render-ahead ring depth for this runner only, leaving the global
	 * {@link AudioSceneRealtimeRunner#renderAheadSlots} that other runners read unchanged.
	 *
	 * @param slots ring depth in buffers, or non-positive for the global default
	 * @return this options object
	 */
	public RealtimeRunnerOptions renderAheadSlots(int slots) {
		this.renderAheadSlots = slots;
		return this;
	}

	/** Returns the configured start frame. */
	public long getStartFrame() { return startFrame; }

	/** Returns the configured loop length in frames. */
	public double getLoopFrames() { return loopFrames; }

	/**
	 * Returns the ring depth to use: the override when positive, otherwise the global
	 * {@link AudioSceneRealtimeRunner#renderAheadSlots}.
	 */
	public int resolveRenderAheadSlots() {
		return renderAheadSlots > 0 ? renderAheadSlots : AudioSceneRealtimeRunner.renderAheadSlots;
	}

	/** Creates the position window these options describe. */
	public RealtimePositionWindow createWindow() {
		return new RealtimePositionWindow(startFrame, loopFrames);
	}
}
