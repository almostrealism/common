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
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.DoubleStream;

public class WaveDetailsFactory implements CodeFeatures {

	public static boolean enableFreqSimilarity = false;

	public static int defaultBins = 32; // 16;
	public static double defaultWindow = 0.25; // 0.125;

	protected static WaveDetailsFactory defaultFactory;

	private final int sampleRate;
	private final double fftSampleRate;

	private final int freqBins;
	private final int scaleBins;
	private final int scaleTime;
	private final PackedCollection buffer;
	private final Evaluable<PackedCollection> sum;

	private WaveDataFeatureProvider featureProvider;

	public WaveDetailsFactory(int sampleRate) {
		this(defaultBins, defaultWindow, sampleRate);
	}

	public WaveDetailsFactory(int freqBins, double sampleWindow, int sampleRate) {
		if (WaveData.FFT_POOL_BINS % freqBins != 0) {
			throw new IllegalArgumentException("FFT bins must be a factor of " + WaveData.FFT_POOL_BINS);
		}

		this.sampleRate = sampleRate;
		this.freqBins = freqBins;
		this.scaleBins = WaveData.FFT_POOL_BINS / freqBins;

		this.fftSampleRate = sampleRate / (double) (WaveData.FFT_BINS * WaveData.FFT_POOL);
		this.scaleTime = (int) (sampleWindow * fftSampleRate);

		buffer = new PackedCollection(shape(scaleTime, WaveData.FFT_POOL_BINS));
		sum = cv(shape(scaleTime, WaveData.FFT_POOL_BINS, 1), 0)
				.enumerate(2, 1)
				.enumerate(2, scaleBins)
				.enumerate(2, scaleTime)
				.traverse(3)
				.sum()
				.get();
	}

	public int getSampleRate() { return sampleRate; }

	public WaveDataFeatureProvider getFeatureProvider() {
		return featureProvider;
	}

	public void setFeatureProvider(WaveDataFeatureProvider featureProvider) {
		this.featureProvider = featureProvider;
	}

	public WaveDetails forFile(String file) {
		return forProvider(new FileWaveDataProvider(file), null);
	}

	public WaveDetails forProvider(WaveDataProvider provider) {
		return forProvider(provider, null);
	}

	public WaveDetails forProvider(WaveDataProvider provider, WaveDetails existing) {
		if (provider == null) return existing;

		if (existing == null) {
			existing = new WaveDetails(provider.getIdentifier(), provider.getSampleRate());
		}

		WaveData data = provider.get(getSampleRate());
		if (data == null) return existing;

		existing.setSampleRate(data.getSampleRate());
		existing.setChannelCount(data.getChannelCount());
		existing.setFrameCount(data.getFrameCount());
		existing.setData(data.getData());

		if (existing.getFreqFrameCount() <= 1) {
			PackedCollection fft = null;

			for (int c = 0; c < data.getChannelCount(); c++) {
				PackedCollection df = data.fft(c, true);

				int count = df.getShape().length(0) / scaleTime;
				if (df.getShape().length(0) % scaleTime != 0) count++;

				if (fft == null) {
					fft = new PackedCollection(data.getChannelCount(), count, freqBins, 1);
				}

				processFft(df, fft.range(shape(count, freqBins, 1), c * freqBins));
			}

			if (fft != null) {
				existing.setFreqSampleRate(fftSampleRate / scaleTime);
				existing.setFreqChannelCount(fft.getShape().length(0));
				existing.setFreqFrameCount(fft.getShape().length(1));
				existing.setFreqBinCount(freqBins);
				existing.setFreqData(fft);
			}
		}

		if (featureProvider != null) {
			PackedCollection features = prepareFeatures(provider);
			existing.setFeatureSampleRate(featureProvider.getFeatureSampleRate());
			existing.setFeatureChannelCount(1);
			existing.setFeatureBinCount(features.getShape().length(1));
			existing.setFeatureFrameCount(features.getShape().length(0));
			existing.setFeatureData(features);
		}

		return existing;
	}

	public double similarity(WaveDetails a, WaveDetails b) {
		if (a.getFeatureData() != null && b.getFeatureData() != null) {
			int limit = Math.max(a.getValidFeatureFrameCount(), b.getValidFeatureFrameCount());
			return productSimilarity(cp(a.getFeatureData()), cp(b.getFeatureData()), limit);
		} else if (enableFreqSimilarity && a.getFreqData() != null && b.getFreqData() != null) {
			return -WaveDetails.differenceSimilarity(a.getFreqData(0), b.getFreqData(0));
		} else {
			return -Double.MAX_VALUE;
		}
	}

	public double productSimilarity(Producer<PackedCollection> a, Producer<PackedCollection> b, int limit) {
		double[] values = multiply(a, b).sum(1)
				.divide(multiply(length(1, a), length(1, b)))
				.evaluate().doubleStream().limit(limit).toArray();
		if (values.length == 0) return -1.0;

		int skip = 0;
		int total = values.length;
		if (total > 10) {
			// If there is enough material, remove the bottom 10%
			// and the top 2 values to reduce outlier effects
			skip = (int) (values.length * 0.1);
			total = total - skip - 2;
		}

		// log("Skipping " + skip + " of " + values.length + " values, averaging " + total + " values");
		return DoubleStream.of(values).sorted().skip(skip).limit(total).average().orElseThrow();
	}

	protected PackedCollection processFft(PackedCollection fft, PackedCollection output) {
		if (fft.getShape().length(0) < 1) {
			throw new IllegalArgumentException();
		}

		int count = output.getShape().length(0);
		TraversalPolicy inShape = shape(scaleTime, WaveData.FFT_POOL_BINS);

		for (int i = 0; i < count; i++) {
			PackedCollection in;

			if (fft.getMemLength() < (i + 1) * inShape.getTotalSize()) {
				buffer.setMem(0, fft, i * inShape.getTotalSize(), fft.getMemLength() - i * inShape.getTotalSize());
				in = buffer;
			} else {
				in = fft.range(inShape, i * inShape.getTotalSize());
			}

			PackedCollection out = output.range(shape(freqBins, 1), i * freqBins);
			sum.into(out.traverseEach()).evaluate(in.traverseEach());
		}

		return output;
	}

	protected PackedCollection prepareFeatures(WaveDataProvider provider) {
		PackedCollection features = featureProvider.computeFeatures(provider);

		if (features.getShape().length(0) < 1) {
			throw new IllegalArgumentException();
		}

		return features;
	}

	public static WaveDetailsFactory getDefault() {
		if (defaultFactory == null) {
			defaultFactory = new WaveDetailsFactory(OutputLine.sampleRate);
		}

		return defaultFactory;
	}
}
