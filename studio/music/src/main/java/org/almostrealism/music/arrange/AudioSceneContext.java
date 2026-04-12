/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.music.arrange;

import io.almostrealism.relation.Factor;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.IdentityFactor;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * Provides rendering context for pattern audio generation.
 *
 * <p>{@code AudioSceneContext} is the central context object passed through the pattern
 * rendering pipeline. It contains all the information needed to convert pattern elements
 * into actual audio samples:</p>
 *
 * <h2>Time and Position Conversion</h2>
 * <ul>
 *   <li>{@code measures}: Total measures in the arrangement</li>
 *   <li>{@code frames}: Total audio frames in the destination</li>
 *   <li>{@code frameForPosition}: Converts measure position to frame offset</li>
 *   <li>{@code timeForDuration}: Converts duration (in measures) to seconds</li>
 * </ul>
 *
 * <h2>Musical Context</h2>
 * <ul>
 *   <li>{@code scaleForPosition}: Returns the active scale at a given measure position</li>
 *   <li>{@code activityBias}: Global bias for pattern activity selection</li>
 *   <li>{@code automationLevel}: Function providing automation modulation</li>
 *   <li>{@code sections}: Channel sections defining activity regions</li>
 * </ul>
 *
 * <h2>Rendering Target</h2>
 * <ul>
 *   <li>{@code destination}: The buffer where rendered audio is summed</li>
 *   <li>{@code channels}: Which channels are being rendered</li>
 * </ul>
 *
 * <h2>Real-Time Usage</h2>
 *
 * <p>For real-time rendering, {@code frameForPosition} returns absolute frame positions
 * relative to the start of the arrangement. The rendering pipeline uses
 * {@code startFrame} and {@code frameCount} parameters to select only the notes
 * overlapping the current buffer window, then writes into the buffer at
 * buffer-relative offsets.</p>
 *
 * @see PatternSystemManager#sum
 * @see PatternLayerManager#sum
 * @see ScaleTraversalStrategy
 *
 * @author Michael Murray
 */
public class AudioSceneContext {
	/** Total number of measures in the arrangement. */
	private int measures;

	/** Total number of audio frames in the destination buffer. */
	private int frames;

	/** The channels being rendered in this context. */
	private List<ChannelInfo> channels;

	/** Converts a measure position to an absolute frame offset. */
	private DoubleToIntFunction frameForPosition;

	/** Converts a duration in measures to a duration in seconds. */
	private DoubleUnaryOperator timeForDuration;

	/** Returns the active musical scale at a given measure position. */
	private DoubleFunction<Scale<?>> scaleForPosition;

	/** Global bias applied to pattern activity selection. */
	private double activityBias;

	/** Function that maps automation parameter data to a modulation factor. */
	private Function<PackedCollection, Factor<PackedCollection>> automationLevel;

	/** The audio buffer where rendered pattern audio is accumulated. */
	private PackedCollection destination;

	/** Channel sections that define activity regions within the arrangement. */
	private List<ChannelSection> sections;

	/** Creates an AudioSceneContext with an identity automation level. */
	public AudioSceneContext() {
		automationLevel = c -> new IdentityFactor<>();
	}

	/** Returns the total number of measures in the arrangement. */
	public int getMeasures() {
		return measures;
	}

	/** Sets the total number of measures in the arrangement. */
	public void setMeasures(int measures) {
		this.measures = measures;
	}

	/** Returns the total number of audio frames in the destination buffer. */
	public int getFrames() {
		return frames;
	}

	/** Sets the total number of audio frames in the destination buffer. */
	public void setFrames(int frames) {
		this.frames = frames;
	}

	/** Returns the list of channels being rendered. */
	public List<ChannelInfo> getChannels() {
		return channels;
	}

	/** Sets the list of channels being rendered. */
	public void setChannels(List<ChannelInfo> channels) {
		this.channels = channels;
	}

	/**
	 * Returns {@code true} if the given pattern channel index is included in this context.
	 *
	 * @param channel the pattern channel index to check
	 * @return {@code true} if the channel is present in the channels list
	 */
	public boolean includesChannel(int channel) {
		return getChannels() != null && getChannels().stream().anyMatch(c -> c.getPatternChannel() == channel);
	}

	/** Returns the function that converts a measure position to an absolute frame offset. */
	public DoubleToIntFunction getFrameForPosition() {
		return frameForPosition;
	}

	/** Sets the function that converts a measure position to an absolute frame offset. */
	public void setFrameForPosition(DoubleToIntFunction frameForPosition) {
		this.frameForPosition = frameForPosition;
	}

	/**
	 * Converts a measure position to an absolute frame offset.
	 *
	 * @param pos position in measures
	 * @return the corresponding frame offset
	 */
	public int frameForPosition(double pos) {
		return frameForPosition.applyAsInt(pos);
	}

	/** Returns the function that converts a duration in measures to a duration in seconds. */
	public DoubleUnaryOperator getTimeForDuration() {
		return timeForDuration;
	}

	/** Sets the function that converts a duration in measures to a duration in seconds. */
	public void setTimeForDuration(DoubleUnaryOperator timeForDuration) {
		this.timeForDuration = timeForDuration;
	}

	/**
	 * Converts a measure position to a time in seconds.
	 *
	 * @param pos position in measures
	 * @return the corresponding time in seconds
	 */
	public double timeForPosition(double pos) {
		return getTimeForDuration().applyAsDouble(pos);
	}

	/** Returns the function that provides the active scale at a given measure position. */
	public DoubleFunction<Scale<?>> getScaleForPosition() {
		return scaleForPosition;
	}

	/** Sets the function that provides the active scale at a given measure position. */
	public void setScaleForPosition(DoubleFunction<Scale<?>> scaleForPosition) {
		this.scaleForPosition = scaleForPosition;
	}

	/** Returns the global activity bias applied to pattern selection. */
	public double getActivityBias() { return activityBias; }

	/** Sets the global activity bias applied to pattern selection. */
	public void setActivityBias(double activityBias) { this.activityBias = activityBias; }

	/** Returns the automation level function used to modulate pattern parameters. */
	public Function<PackedCollection, Factor<PackedCollection>> getAutomationLevel() {
		return automationLevel;
	}

	/** Sets the automation level function used to modulate pattern parameters. */
	public void setAutomationLevel(Function<PackedCollection, Factor<PackedCollection>> automationLevel) {
		this.automationLevel = automationLevel;
	}

	/** Returns the destination buffer where rendered audio is accumulated. */
	public PackedCollection getDestination() {
		return destination;
	}

	/** Sets the destination buffer where rendered audio is accumulated. */
	public void setDestination(PackedCollection destination) {
		this.destination = destination;
	}

	/** Returns the list of channel sections defining activity regions. */
	public List<ChannelSection> getSections() {
		return sections;
	}

	/** Sets the list of channel sections defining activity regions. */
	public void setSections(List<ChannelSection> sections) {
		this.sections = sections;
	}

	/**
	 * Returns the channel section active at the given measure position, or {@code null}
	 * if no section covers that position.
	 *
	 * @param measure the measure position to look up
	 * @return the active {@link ChannelSection}, or {@code null}
	 */
	public ChannelSection getSection(double measure) {
		if (sections == null || sections.isEmpty()) return null;

		return sections.stream()
				.filter(s -> s.getPosition() <= measure && measure < (s.getPosition() + s.getLength()))
				.findFirst()
				.orElse(null);
	}
}
