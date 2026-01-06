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

package org.almostrealism.audio.arrange;

import io.almostrealism.relation.Factor;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.IdentityFactor;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

public class AudioSceneContext {
	private int measures;
	private int frames;
	private List<ChannelInfo> channels;

	private DoubleToIntFunction frameForPosition;
	private DoubleUnaryOperator timeForDuration;
	private DoubleFunction<Scale<?>> scaleForPosition;
	private double activityBias;

	private Function<PackedCollection, Factor<PackedCollection>> automationLevel;

	private PackedCollection destination;

	private List<ChannelSection> sections;

	public AudioSceneContext() {
		automationLevel = c -> new IdentityFactor<>();
	}

	public int getMeasures() {
		return measures;
	}

	public void setMeasures(int measures) {
		this.measures = measures;
	}

	public int getFrames() {
		return frames;
	}

	public void setFrames(int frames) {
		this.frames = frames;
	}

	public List<ChannelInfo> getChannels() {
		return channels;
	}

	public void setChannels(List<ChannelInfo> channels) {
		this.channels = channels;
	}

	public boolean includesChannel(int channel) {
		return getChannels() != null && getChannels().stream().anyMatch(c -> c.getPatternChannel() == channel);
	}

	public DoubleToIntFunction getFrameForPosition() {
		return frameForPosition;
	}

	public void setFrameForPosition(DoubleToIntFunction frameForPosition) {
		this.frameForPosition = frameForPosition;
	}

	public int frameForPosition(double pos) {
		return frameForPosition.applyAsInt(pos);
	}

	public DoubleUnaryOperator getTimeForDuration() {
		return timeForDuration;
	}

	public void setTimeForDuration(DoubleUnaryOperator timeForDuration) {
		this.timeForDuration = timeForDuration;
	}

	public double timeForPosition(double pos) {
		return getTimeForDuration().applyAsDouble(pos);
	}

	public DoubleFunction<Scale<?>> getScaleForPosition() {
		return scaleForPosition;
	}

	public void setScaleForPosition(DoubleFunction<Scale<?>> scaleForPosition) {
		this.scaleForPosition = scaleForPosition;
	}

	public double getActivityBias() { return activityBias; }
	public void setActivityBias(double activityBias) { this.activityBias = activityBias; }

	public Function<PackedCollection, Factor<PackedCollection>> getAutomationLevel() {
		return automationLevel;
	}

	public void setAutomationLevel(Function<PackedCollection, Factor<PackedCollection>> automationLevel) {
		this.automationLevel = automationLevel;
	}

	public PackedCollection getDestination() {
		return destination;
	}

	public void setDestination(PackedCollection destination) {
		this.destination = destination;
	}

	public List<ChannelSection> getSections() {
		return sections;
	}

	public void setSections(List<ChannelSection> sections) {
		this.sections = sections;
	}

	public ChannelSection getSection(double measure) {
		if (sections == null || sections.isEmpty()) return null;

		return sections.stream()
				.filter(s -> s.getPosition() <= measure && measure < (s.getPosition() + s.getLength()))
				.findFirst()
				.orElse(null);
	}
}
