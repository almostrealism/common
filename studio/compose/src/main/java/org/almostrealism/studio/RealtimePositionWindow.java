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
 * Maps a real-time runner's render cursor onto a pattern position inside a looping window.
 *
 * <p>A runner's render cursor restarts at frame {@code 0} whenever its render-ahead stream is
 * restarted ({@link PatternRenderStream#stop()} zeroes it). A window lets a caller choose where
 * that restart lands: the pattern position rendered for cursor frame {@code f} is
 * {@code (startFrame + f) mod loopFrames}. A non-positive {@code loopFrames} disables the
 * modulo, so the position is simply {@code startFrame + f}; the default window
 * ({@code startFrame = 0}, no loop) therefore reproduces the cursor unchanged.</p>
 *
 * <p>The loop length is a {@code double} because a tempo-derived loop is usually a fractional
 * number of frames, and a looping player wraps at that unrounded value. Rounding it here would
 * drift the rendered positions against the player's read head by a fraction of a frame per
 * pass.</p>
 *
 * <p>The start frame is written by the runner's owner between ticks (see
 * {@link RealtimeRunner#retarget(long)}) and read by the render-ahead producer thread, so it is
 * volatile.</p>
 */
public class RealtimePositionWindow {

	/** Pattern position of render cursor frame {@code 0}. */
	private volatile long startFrame;

	/** Loop length in frames; non-positive means positions do not wrap. */
	private volatile double loopFrames;

	/** Creates a window that leaves the render cursor unchanged. */
	public RealtimePositionWindow() {
		this(0, 0);
	}

	/**
	 * Creates a window starting at {@code startFrame} that wraps at {@code loopFrames}.
	 *
	 * @param startFrame pattern position of render cursor frame {@code 0}
	 * @param loopFrames loop length in frames, or a non-positive value for no wrap
	 */
	public RealtimePositionWindow(long startFrame, double loopFrames) {
		this.loopFrames = loopFrames;
		setStartFrame(startFrame);
	}

	/** Returns the pattern position of render cursor frame {@code 0}. */
	public long getStartFrame() { return startFrame; }

	/**
	 * Sets the pattern position of render cursor frame {@code 0}, wrapped into the loop.
	 *
	 * @param startFrame the new start position in frames
	 */
	public void setStartFrame(long startFrame) {
		this.startFrame = (long) Math.floor(wrap(startFrame));
	}

	/** Returns the loop length in frames, or a non-positive value when positions do not wrap. */
	public double getLoopFrames() { return loopFrames; }

	/**
	 * Sets the loop length. The start frame is re-wrapped into the new length.
	 *
	 * @param loopFrames loop length in frames, or a non-positive value for no wrap
	 */
	public void setLoopFrames(double loopFrames) {
		this.loopFrames = loopFrames;
		setStartFrame(startFrame);
	}

	/** Returns {@code true} when positions wrap at {@link #getLoopFrames()}. */
	public boolean isLooping() { return loopFrames > 0; }

	/**
	 * Returns the pattern position for a render cursor frame.
	 *
	 * @param cursorFrame frames since the render stream was (re)started
	 * @return {@code floor((startFrame + cursorFrame) mod loopFrames)}, or
	 *         {@code startFrame + cursorFrame} when the window does not loop
	 */
	public long position(long cursorFrame) {
		return (long) Math.floor(wrap((double) startFrame + cursorFrame));
	}

	/**
	 * Wraps a frame position into {@code [0, loopFrames)}; returns it unchanged when the
	 * window does not loop.
	 *
	 * @param frame the frame position to wrap
	 * @return the wrapped position
	 */
	private double wrap(double frame) {
		double loop = loopFrames;
		if (loop <= 0) return frame;
		double wrapped = frame - Math.floor(frame / loop) * loop;
		return wrapped >= loop ? 0 : wrapped;
	}
}
