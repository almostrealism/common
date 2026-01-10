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

package org.almostrealism.audio.arrange;

import io.almostrealism.relation.Factor;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * Extended context for incremental pattern rendering in real-time mode.
 *
 * <p>{@code PatternRenderContext} wraps an {@link AudioSceneContext} and adds
 * frame range information needed for buffer-based rendering. It enables pattern
 * rendering to work with specific frame ranges rather than the full arrangement.</p>
 *
 * <h2>Frame Range Awareness</h2>
 *
 * <p>This context tracks:</p>
 * <ul>
 *   <li>{@code startFrame}: The absolute frame position where the current buffer starts</li>
 *   <li>{@code frameCount}: The number of frames in the current buffer</li>
 * </ul>
 *
 * <h2>Position Conversion</h2>
 *
 * <p>Key methods for real-time rendering:</p>
 * <ul>
 *   <li>{@link #measureToBufferOffset}: Converts measure position to buffer-relative frame offset</li>
 *   <li>{@link #overlapsFrameRange}: Checks if a measure range overlaps with current buffer</li>
 *   <li>{@link #frameToMeasure}: Converts absolute frame to measure position</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AudioSceneContext baseContext = scene.getContext(channel);
 * PatternRenderContext renderContext = new PatternRenderContext(baseContext, startFrame, bufferSize);
 *
 * // Check if a pattern element overlaps with the buffer
 * if (renderContext.overlapsFrameRange(elementStart, elementEnd)) {
 *     int bufferOffset = renderContext.measureToBufferOffset(elementStart);
 *     // Render element to buffer at bufferOffset
 * }
 * }</pre>
 *
 * @see AudioSceneContext
 * @see org.almostrealism.audio.pattern.PatternSystemManager#sum(java.util.function.Supplier, ChannelInfo, int, int)
 * @see org.almostrealism.audio.pattern.PatternLayerManager#sum(java.util.function.Supplier, ChannelInfo.Voicing, ChannelInfo.StereoChannel, int, int)
 *
 * @author Michael Murray
 */
public class PatternRenderContext extends AudioSceneContext {
	private final AudioSceneContext delegate;
	private final int startFrame;
	private final int frameCount;

	/**
	 * Creates a new PatternRenderContext wrapping the given context.
	 *
	 * @param delegate The base AudioSceneContext to wrap
	 * @param startFrame The absolute starting frame for the current buffer
	 * @param frameCount The number of frames in the buffer
	 */
	public PatternRenderContext(AudioSceneContext delegate, int startFrame, int frameCount) {
		this.delegate = delegate;
		this.startFrame = startFrame;
		this.frameCount = frameCount;
	}

	/**
	 * Returns the absolute starting frame of the current buffer.
	 */
	public int getStartFrame() {
		return startFrame;
	}

	/**
	 * Returns the number of frames in the current buffer.
	 */
	public int getFrameCount() {
		return frameCount;
	}

	/**
	 * Returns the absolute ending frame of the current buffer (exclusive).
	 */
	public int getEndFrame() {
		return startFrame + frameCount;
	}

	/**
	 * Converts an absolute frame position to a measure position.
	 *
	 * @param frame The absolute frame position
	 * @return The corresponding measure position
	 */
	public double frameToMeasure(int frame) {
		if (delegate.getFrames() == 0 || delegate.getMeasures() == 0) {
			return 0.0;
		}
		double framesPerMeasure = (double) delegate.getFrames() / delegate.getMeasures();
		return frame / framesPerMeasure;
	}

	/**
	 * Converts a measure position to a frame offset within the current buffer.
	 *
	 * <p>Returns -1 if the position is outside the current frame range.</p>
	 *
	 * @param measure The measure position
	 * @return The buffer-relative frame offset, or -1 if outside the buffer
	 */
	public int measureToBufferOffset(double measure) {
		int absoluteFrame = delegate.frameForPosition(measure);
		int relativeFrame = absoluteFrame - startFrame;

		if (relativeFrame < 0 || relativeFrame >= frameCount) {
			return -1;
		}
		return relativeFrame;
	}

	/**
	 * Converts an absolute frame position to a buffer-relative offset.
	 *
	 * <p>Returns -1 if the position is outside the current frame range.</p>
	 *
	 * @param absoluteFrame The absolute frame position
	 * @return The buffer-relative offset, or -1 if outside the buffer
	 */
	public int absoluteToBufferOffset(int absoluteFrame) {
		int relativeFrame = absoluteFrame - startFrame;
		if (relativeFrame < 0 || relativeFrame >= frameCount) {
			return -1;
		}
		return relativeFrame;
	}

	/**
	 * Checks if a measure range overlaps with the current frame range.
	 *
	 * @param startMeasure Start of the measure range
	 * @param endMeasure End of the measure range
	 * @return true if the ranges overlap
	 */
	public boolean overlapsFrameRange(double startMeasure, double endMeasure) {
		int rangeStart = delegate.frameForPosition(startMeasure);
		int rangeEnd = delegate.frameForPosition(endMeasure);
		return rangeStart < getEndFrame() && rangeEnd > startFrame;
	}

	/**
	 * Checks if an absolute frame range overlaps with the current buffer.
	 *
	 * @param rangeStart Start frame (absolute)
	 * @param rangeEnd End frame (absolute)
	 * @return true if the ranges overlap
	 */
	public boolean overlapsAbsoluteFrameRange(int rangeStart, int rangeEnd) {
		return rangeStart < getEndFrame() && rangeEnd > startFrame;
	}

	/**
	 * Returns the measure position at the start of the current buffer.
	 */
	public double getStartMeasure() {
		return frameToMeasure(startFrame);
	}

	/**
	 * Returns the measure position at the end of the current buffer.
	 */
	public double getEndMeasure() {
		return frameToMeasure(getEndFrame());
	}

	// Delegate methods to wrapped context

	@Override
	public int getMeasures() {
		return delegate.getMeasures();
	}

	@Override
	public void setMeasures(int measures) {
		delegate.setMeasures(measures);
	}

	@Override
	public int getFrames() {
		return delegate.getFrames();
	}

	@Override
	public void setFrames(int frames) {
		delegate.setFrames(frames);
	}

	@Override
	public List<ChannelInfo> getChannels() {
		return delegate.getChannels();
	}

	@Override
	public void setChannels(List<ChannelInfo> channels) {
		delegate.setChannels(channels);
	}

	@Override
	public boolean includesChannel(int channel) {
		return delegate.includesChannel(channel);
	}

	@Override
	public DoubleToIntFunction getFrameForPosition() {
		return delegate.getFrameForPosition();
	}

	@Override
	public void setFrameForPosition(DoubleToIntFunction frameForPosition) {
		delegate.setFrameForPosition(frameForPosition);
	}

	@Override
	public int frameForPosition(double pos) {
		return delegate.frameForPosition(pos);
	}

	@Override
	public DoubleUnaryOperator getTimeForDuration() {
		return delegate.getTimeForDuration();
	}

	@Override
	public void setTimeForDuration(DoubleUnaryOperator timeForDuration) {
		delegate.setTimeForDuration(timeForDuration);
	}

	@Override
	public double timeForPosition(double pos) {
		return delegate.timeForPosition(pos);
	}

	@Override
	public DoubleFunction<Scale<?>> getScaleForPosition() {
		return delegate.getScaleForPosition();
	}

	@Override
	public void setScaleForPosition(DoubleFunction<Scale<?>> scaleForPosition) {
		delegate.setScaleForPosition(scaleForPosition);
	}

	@Override
	public double getActivityBias() {
		return delegate.getActivityBias();
	}

	@Override
	public void setActivityBias(double activityBias) {
		delegate.setActivityBias(activityBias);
	}

	@Override
	public Function<PackedCollection, Factor<PackedCollection>> getAutomationLevel() {
		return delegate.getAutomationLevel();
	}

	@Override
	public void setAutomationLevel(Function<PackedCollection, Factor<PackedCollection>> automationLevel) {
		delegate.setAutomationLevel(automationLevel);
	}

	@Override
	public PackedCollection getDestination() {
		return delegate.getDestination();
	}

	@Override
	public void setDestination(PackedCollection destination) {
		delegate.setDestination(destination);
	}

	@Override
	public List<ChannelSection> getSections() {
		return delegate.getSections();
	}

	@Override
	public void setSections(List<ChannelSection> sections) {
		delegate.setSections(sections);
	}

	@Override
	public ChannelSection getSection(double measure) {
		return delegate.getSection(measure);
	}
}
