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

package org.almostrealism.audio.data;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;

import java.util.HashMap;
import java.util.Map;

public class WaveDetails implements CodeFeatures {
	public static boolean enableNormalizeSimilarity = false;

	private static final Map<Integer, Evaluable<PackedCollection>> differenceCalc = new HashMap<>();

	private final String identifier;

	private int sampleRate;
	private int channelCount;
	private int frameCount;
	private PackedCollection data;
	private boolean silent;
	private boolean persistent;

	private double freqSampleRate;
	private int freqChannelCount;
	private int freqBinCount;
	private int freqFrameCount;
	private PackedCollection freqData;

	private double featureSampleRate;
	private int featureChannelCount;
	private int featureBinCount;
	private int featureFrameCount;
	private PackedCollection featureData;

	private Map<String, Double> similarities;

	public WaveDetails() {
		this(null);
	}

	public WaveDetails(String identifier) {
		this(identifier, -1);
	}

	public WaveDetails(String identifier, int sampleRate) {
		this.identifier = identifier;
		this.sampleRate = sampleRate;
		this.similarities = new HashMap<>();
	}

	public String getIdentifier() {
		return identifier;
	}

	public int getSampleRate() {
		return sampleRate;
	}

	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	public int getChannelCount() {
		return channelCount;
	}

	public void setChannelCount(int channelCount) {
		this.channelCount = channelCount;
	}

	public int getFrameCount() {
		return frameCount;
	}

	public void setFrameCount(int frameCount) {
		this.frameCount = frameCount;
	}

	public PackedCollection getData() {
		return data;
	}

	public void setData(PackedCollection data) {
		this.data = data;
	}

	public boolean isSilent() { return silent; }
	public void setSilent(boolean silent) { this.silent = silent; }

	public boolean isPersistent() { return persistent; }
	public void setPersistent(boolean persistent) {
		this.persistent = persistent;
	}

	public double getFreqSampleRate() {
		return freqSampleRate;
	}

	public void setFreqSampleRate(double freqSampleRate) {
		this.freqSampleRate = freqSampleRate;
	}

	public int getFreqChannelCount() {
		return freqChannelCount;
	}

	public void setFreqChannelCount(int freqChannelCount) {
		this.freqChannelCount = freqChannelCount;
	}

	public int getFreqBinCount() {
		return freqBinCount;
	}

	public void setFreqBinCount(int freqBinCount) {
		this.freqBinCount = freqBinCount;
	}

	public int getFreqFrameCount() {
		return freqFrameCount;
	}

	public void setFreqFrameCount(int freqFrameCount) {
		this.freqFrameCount = freqFrameCount;
	}

	public PackedCollection getFreqData() {
		return freqData;
	}

	public PackedCollection getFreqData(int channel) {
		return getFreqData().get(channel, shape(getFreqFrameCount(), getFreqBinCount(), 1));
	}

	public void setFreqData(PackedCollection freqData) {
		this.freqData = freqData;
	}

	public double getFeatureSampleRate() {
		return featureSampleRate;
	}

	public void setFeatureSampleRate(double featureSampleRate) {
		this.featureSampleRate = featureSampleRate;
	}

	public int getFeatureChannelCount() {
		return featureChannelCount;
	}

	public void setFeatureChannelCount(int featureChannelCount) {
		this.featureChannelCount = featureChannelCount;
	}

	public int getFeatureBinCount() {
		return featureBinCount;
	}

	public void setFeatureBinCount(int featureBinCount) {
		this.featureBinCount = featureBinCount;
	}

	public int getFeatureFrameCount() {
		return featureFrameCount;
	}

	public void setFeatureFrameCount(int featureFrameCount) {
		this.featureFrameCount = featureFrameCount;
	}

	public PackedCollection getFeatureData() {
		return featureData;
	}

	public void setFeatureData(PackedCollection featureData) {
		this.featureData = featureData;
	}

	public PackedCollection getFeatureData(boolean transpose) {
		if (transpose) {
			return featureData.transpose();
		} else {
			return featureData;
		}
	}

	public int getValidFeatureFrameCount() {
		double duration = (double) getFrameCount() / (double) getSampleRate();
		int limit = (int) Math.ceil(duration * getFeatureSampleRate());
		return Math.min(getFeatureFrameCount(), limit);
	}

	public Map<String, Double> getSimilarities() {
		return similarities;
	}

	public void setSimilarities(Map<String, Double> similarities) {
		this.similarities = similarities;
	}

	private static int frameCount(PackedCollection data) {
		return data.getShape().length(0);
	}

	private static int binCount(PackedCollection data) {
		return data.getShape().length(1);
	}

	private static Evaluable<PackedCollection> differenceMagnitude(int bins) {
		return differenceCalc.computeIfAbsent(bins, key -> Ops.op(o ->
						o.cv(new TraversalPolicy(bins, 1), 0)
				.subtract(o.cv(new TraversalPolicy(bins, 1), 1))
				.traverseEach()
				.magnitude()).get());
	}

	public static double differenceSimilarity(PackedCollection a, PackedCollection b) {
		if (a.getShape().getDimensions() != 2 && a.getShape().getDimensions() != 3) {
			throw new IllegalArgumentException();
		} else if (a.getShape().getDimensions() != b.getShape().getDimensions()) {
			throw new IllegalArgumentException();
		}

		int bins = 0;
		int n;

		if (binCount(a) == binCount(b)) {
			n = Math.min(frameCount(a), frameCount(b));
			bins = binCount(a);
		} else {
			// Feature data with different shapes are not easily comparable
			n = 0;
		}

		TraversalPolicy overlap = new TraversalPolicy(true, n, bins, 1);

		double d = 0.0;

		if (n > 0) {
			PackedCollection featuresA = a.range(overlap).traverse(1);
			PackedCollection featuresB = b.range(overlap).traverse(1);
			PackedCollection diff = differenceMagnitude(bins)
					.evaluate(featuresA, featuresB);
			d += diff.doubleStream().sum();
		}

		if (frameCount(a) > n) {
			d += a.range(new TraversalPolicy(frameCount(a) - n, binCount(a), 1), overlap.getTotalSize())
					.doubleStream().map(Math::abs).sum();
		}

		if (frameCount(b) > n) {
			d += b.range(new TraversalPolicy(frameCount(b) - n, binCount(b), 1), overlap.getTotalSize())
					.doubleStream().map(Math::abs).sum();
		}

		if (enableNormalizeSimilarity) {
			double max = Math.max(
					frameCount(a) <= 0 ? 0.0 : a.doubleStream().map(Math::abs).max().orElse(0.0),
					frameCount(b) <= 0 ? 0.0 : b.doubleStream().map(Math::abs).max().orElse(0.0));
			max = max * bins * Math.max(frameCount(a), frameCount(b));
			double r = max == 0 ? Double.MAX_VALUE : (d / max);

			if (r > 1.0 && max != 0) {
				Console.root().features(WaveDetails.class).warn("Similarity = " + r);
			}

			return r;
		} else {
			return d;
		}
	}
}
