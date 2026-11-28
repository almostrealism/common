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
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.DoubleStream;

/**
 * Factory for creating WaveDetails with FFT analysis and feature extraction.
 *
 * <p>WaveDetailsFactory computes detailed audio analysis including FFT-based
 * frequency data and optional feature extraction for similarity comparison.
 * It uses configurable parameters for FFT bin count and time window size.</p>
 *
 * <h2>Analysis Pipeline</h2>
 * <ol>
 *   <li>Load audio data at the factory's sample rate</li>
 *   <li>Compute FFT across time windows</li>
 *   <li>Pool and summarize frequency bins</li>
 *   <li>Optionally extract features via WaveDataFeatureProvider</li>
 * </ol>
 *
 * <h2>Similarity Computation</h2>
 * <p>The factory can compute similarity scores between samples using either
 * FFT-based difference metrics or feature vector products. Feature similarity
 * is the trimmed mean of per-frame cosine similarities over the frames in which
 * either sample is sounding, with frames in which only one of them is sounding
 * counting as zero.</p>
 *
 * @see WaveDetails
 * @see WaveDataFeatureProvider
 */
public class WaveDetailsFactory implements CodeFeatures {

	/** When true, uses FFT-based difference metrics for similarity when feature data is unavailable. */
	public static boolean enableFreqSimilarity = false;

	/** Default number of frequency bins in the scaled FFT output. */
	public static int defaultBins = 32; // 16;

	/** Default time window size in seconds used for FFT aggregation. */
	public static double defaultWindow = 0.25; // 0.125;

	/** Shared default factory instance, created lazily at the default output sample rate. */
	protected static WaveDetailsFactory defaultFactory;

	/** Default batch size for batched similarity computation. */
	public static final int SIMILARITY_BATCH_SIZE = 100;

	/** Per-(frames, bins) cache of compiled cosine similarity evaluables. */
	private static final Map<Long, Evaluable<PackedCollection>> cosineCalc = new ConcurrentHashMap<>();

	/** Audio sample rate in Hz used when loading and analyzing audio data. */
	private final int sampleRate;

	/** Effective sample rate (in Hz) of one FFT time slice after pooling. */
	private final double fftSampleRate;

	/** Number of frequency bins in each output time frame after scaling. */
	private final int freqBins;

	/** Scale factor: number of raw FFT pool bins compressed into one output bin. */
	private final int scaleBins;

	/** Number of raw FFT time slices per output time frame. */
	private final int scaleTime;

	/** Reusable scratch buffer for the last partial FFT window. */
	private final PackedCollection buffer;

	/** Compiled sum operation that pools FFT bins within each time window. */
	private final Evaluable<PackedCollection> sum;

	/** Optional provider for computing feature vectors used in similarity scoring. */
	private WaveDataFeatureProvider featureProvider;

	/**
	 * Creates a WaveDetailsFactory at the default bin count and window size.
	 *
	 * @param sampleRate audio sample rate in Hz
	 */
	public WaveDetailsFactory(int sampleRate) {
		this(defaultBins, defaultWindow, sampleRate);
	}

	/**
	 * Creates a WaveDetailsFactory with the specified FFT parameters.
	 *
	 * @param freqBins     number of output frequency bins per time frame (must divide {@link WaveData#FFT_POOL_BINS})
	 * @param sampleWindow time window size in seconds for FFT aggregation
	 * @param sampleRate   audio sample rate in Hz
	 */
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

	/** Returns the audio sample rate used by this factory in Hz. */
	public int getSampleRate() { return sampleRate; }

	/** Returns the feature provider used for computing feature vectors, or null if none is set. */
	public WaveDataFeatureProvider getFeatureProvider() {
		return featureProvider;
	}

	/**
	 * Sets the feature provider used to compute feature vectors for similarity scoring.
	 *
	 * @param featureProvider the feature provider, or null to disable feature extraction
	 */
	public void setFeatureProvider(WaveDataFeatureProvider featureProvider) {
		this.featureProvider = featureProvider;
	}

	/**
	 * Creates a WaveDetails for the audio file at the given path.
	 *
	 * @param file path to the audio file
	 * @return a populated WaveDetails, or null if the file cannot be loaded
	 */
	public WaveDetails forFile(String file) {
		return forProvider(new FileWaveDataProvider(file), null);
	}

	/**
	 * Creates a WaveDetails for the audio supplied by the given provider.
	 *
	 * @param provider the audio data provider
	 * @return a populated WaveDetails, or null if the provider is null
	 */
	public WaveDetails forProvider(WaveDataProvider provider) {
		return forProvider(provider, null);
	}

	/**
	 * Creates or updates a WaveDetails using the audio supplied by the given provider.
	 *
	 * <p>An unreadable provider (null, or one that reports itself not
	 * {@link WaveDataProvider#isReadable() readable}, such as a file already
	 * marked corrupt) is never asked for audio: when no existing details are
	 * supplied the method returns null, and when existing details are supplied
	 * they are completed from what they already hold rather than dividing by the
	 * provider's zero sample rate or dereferencing its absent audio.</p>
	 *
	 * @param provider the audio data provider
	 * @param existing an existing WaveDetails to update, or null to create a new one
	 * @return the populated WaveDetails, or null if the provider is unreadable and
	 *         no existing details were supplied
	 */
	public WaveDetails forProvider(WaveDataProvider provider, WaveDetails existing) {
		boolean readable = provider != null && provider.isReadable();

		if (existing == null) {
			if (!readable) return null;

			existing = new WaveDetails(provider.getIdentifier(), provider.getSampleRate());
		}

		if (readable) {
			WaveData data = provider.get(getSampleRate());
			if (data != null) {
				existing.setSampleRate(data.getSampleRate());
				existing.setChannelCount(data.getChannelCount());
				existing.setFrameCount(data.getFrameCount());
				existing.setData(data.getData());
			}
		}

		return forExisting(existing);
	}

	/**
	 * Computes missing analysis data (FFT and/or features) for existing
	 * {@link WaveDetails}.
	 *
	 * <p>This method transparently handles two input scenarios:</p>
	 * <ul>
	 *   <li><b>Audio waveform present</b> ({@code data} is non-null): FFT is
	 *       computed from the waveform, then features are extracted via the
	 *       autoencoder.</li>
	 *   <li><b>Only frequency data present</b> ({@code data} is null but
	 *       {@code freqData} is non-null): an audio waveform is first
	 *       synthesized from the frequency magnitudes via
	 *       {@link FrequencyToAudioConverter}, then features are extracted.
	 *       This path is used for spatial drawings, which produce frequency
	 *       data directly. The synthesized audio is not persisted — it is
	 *       only needed as an intermediate step for autoencoder encoding.</li>
	 * </ul>
	 *
	 * <p>Frequency data already present is kept, except that multi-channel audio has
	 * its frequency data recomputed when it lacks features for the current feature
	 * model or its frequency data is in an older
	 * {@link WaveDetails#FREQUENCY_LAYOUT_VERSION layout}: data stored before each
	 * channel was analyzed into its own block is corrupt. A record whose features
	 * are current but whose frequency data is not (analysed by a build that had the
	 * features but not the layout fix) is repaired by this without being re-encoded.
	 * Recomputing needs the real audio; frequency data that was itself synthesized
	 * into audio is accepted as it is. Either way the details are left marked with
	 * the current layout.</p>
	 *
	 * @param existing the WaveDetails to complete (modified in place)
	 * @return the same WaveDetails instance with missing fields populated,
	 *         or null if the input is null
	 *
	 * @see FrequencyToAudioConverter
	 * @see AutoEncoderFeatureProvider
	 */
	public WaveDetails forExisting(WaveDetails existing) {
		if (existing == null) return null;

		// If no audio waveform is available but frequency data exists,
		// synthesize audio via IFFT so that feature extraction can proceed.
		// This is the normal path for spatial drawings.
		boolean synthesized = existing.getData() == null && existing.getFreqData() != null;
		if (synthesized) {
			new FrequencyToAudioConverter().convert(existing);
		}

		if (existing.getData() == null) return existing;

		WaveData data = existing.getWaveData();

		boolean predatesFeatureModel = featureProvider != null &&
				existing.getFeatures(featureProvider.getModelIdentifier()) == null;
		boolean staleLayout = !synthesized && !existing.isFrequencyLayoutCurrent();

		if (existing.getFreqFrameCount() <= 1
				|| (data.getChannelCount() > 1 && (predatesFeatureModel || staleLayout))) {
			PackedCollection fft = null;

			for (int c = 0; c < data.getChannelCount(); c++) {
				PackedCollection df = data.fft(c, true);

				int count = df.getShape().length(0) / scaleTime;
				if (df.getShape().length(0) % scaleTime != 0) count++;

				if (fft == null) {
					fft = new PackedCollection(data.getChannelCount(), count, freqBins, 1);
				}

				processFft(df, fft.range(shape(count, freqBins, 1), c * count * freqBins));
			}

			if (fft != null) {
				// The reanalysis path replaces a stale frequency buffer that
				// WaveDetails owns; release it so the superseded native memory
				// does not leak when the new buffer takes its place.
				existing.releaseFreqData();
				existing.setFreqSampleRate(fftSampleRate / scaleTime);
				existing.setFreqChannelCount(fft.getShape().length(0));
				existing.setFreqFrameCount(fft.getShape().length(1));
				existing.setFreqBinCount(freqBins);
				existing.setFreqData(fft);
			}
		}

		// Frequency data synthesized into audio cannot be recomputed from it, so it is
		// accepted as it is; anything computed above is in the current layout.
		existing.setFreqLayoutVersion(WaveDetails.FREQUENCY_LAYOUT_VERSION);

		if (featureProvider != null) {
			String modelId = featureProvider.getModelIdentifier();

			if (existing.getFeatures(modelId) == null) {
				PackedCollection features = featureProvider.computeFeatures(data);
				existing.putFeatures(new WaveFeatures(modelId,
						featureProvider.getFeatureSampleRate(), 1,
						features.getShape().length(1),
						features.getShape().length(0), features));
			}

			existing.setActiveModelIdentifier(modelId);
		}

		return existing;
	}

	/**
	 * Computes similarity between two audio samples using cached evaluables
	 * and pre-computed norms to avoid redundant computation object creation.
	 */
	public double similarity(WaveDetails a, WaveDetails b) {
		if (a.getFeatureData() != null && b.getFeatureData() != null) {
			return cachedProductSimilarity(a, b);
		} else if (enableFreqSimilarity && a.getFreqData() != null && b.getFreqData() != null) {
			return -WaveDetails.differenceSimilarity(a.getFreqData(0), b.getFreqData(0));
		} else {
			return -Double.MAX_VALUE;
		}
	}

	/**
	 * Computes cosine similarity using a cached evaluable that is compiled
	 * once per (frames, bins) combination and reused for all comparisons
	 * with matching dimensions. This eliminates the overhead of creating
	 * ~12 Computation objects and calling alignTraversalAxes ~9 times
	 * per comparison.
	 */
	private double cachedProductSimilarity(WaveDetails a, WaveDetails b) {
		PackedCollection dataA = a.getFeatureData();
		PackedCollection dataB = b.getFeatureData();

		int framesA = dataA.getShape().length(0);
		int framesB = dataB.getShape().length(0);
		int frames = Math.min(framesA, framesB);
		int bins = dataA.getShape().length(1);

		TraversalPolicy rangeShape = new TraversalPolicy(frames, bins, 1);
		PackedCollection inputA = frames < framesA ? dataA.range(rangeShape) : dataA;
		PackedCollection inputB = frames < framesB ? dataB.range(rangeShape) : dataB;

		double[] values = cosineSimilarityEvaluable(frames, bins)
				.evaluate(inputA, inputB).doubleStream().toArray();

		return soundingSimilarity(values, 0, frames,
				a.getValidFeatureFrameCount(), b.getValidFeatureFrameCount());
	}

	/**
	 * Computes similarity between a query and multiple targets using batched
	 * kernel evaluation. Targets are processed in batches of
	 * {@link #SIMILARITY_BATCH_SIZE}, computing multiple cosine similarities
	 * in a single kernel call to reduce per-comparison launch overhead.
	 *
	 * <p>The query tensor is stacked once per batch (not per comparison),
	 * and only target data is copied per-batch. For targets without feature
	 * data or with incompatible bin counts, the result is {@code -Double.MAX_VALUE}.</p>
	 *
	 * @param query   the query WaveDetails
	 * @param targets list of target WaveDetails to compare against
	 * @return array of similarity scores, one per target, in the same order
	 */
	public double[] batchSimilarity(WaveDetails query, List<WaveDetails> targets) {
		if (targets.isEmpty()) return new double[0];

		PackedCollection queryData = query.getFeatureData();
		if (queryData == null) {
			double[] results = new double[targets.size()];
			Arrays.fill(results, -Double.MAX_VALUE);
			return results;
		}

		int queryFrames = queryData.getShape().length(0);
		int bins = queryData.getShape().length(1);
		double[] results = new double[targets.size()];

		int batchSize = SIMILARITY_BATCH_SIZE;
		int batchStart = 0;

		while (batchStart < targets.size()) {
			int batchEnd = Math.min(batchStart + batchSize, targets.size());
			int actualBatch = batchEnd - batchStart;

			int frames = queryFrames;
			for (int i = batchStart; i < batchEnd; i++) {
				PackedCollection targetData = targets.get(i).getFeatureData();
				if (targetData != null) {
					if (targetData.getShape().length(1) != bins) {
						results[i] = -Double.MAX_VALUE;
						continue;
					}
					frames = Math.min(frames, targetData.getShape().length(0));
				}
			}

			int totalFrames = batchSize * frames;
			int elementsPerItem = frames * bins;
			TraversalPolicy rangeShape = new TraversalPolicy(frames, bins, 1);

			PackedCollection queryRange = frames < queryFrames
					? queryData.range(rangeShape) : queryData;

			PackedCollection stackedQuery = new PackedCollection(shape(totalFrames, bins, 1));
			for (int b = 0; b < batchSize; b++) {
				stackedQuery.setFrom(b * elementsPerItem, queryRange, 0, elementsPerItem);
			}

			PackedCollection stackedTargets = new PackedCollection(shape(totalFrames, bins, 1));
			for (int b = 0; b < actualBatch; b++) {
				PackedCollection targetData = targets.get(batchStart + b).getFeatureData();
				if (targetData == null) continue;

				int targetFrames = targetData.getShape().length(0);
				PackedCollection targetRange = frames < targetFrames
						? targetData.range(rangeShape) : targetData;
				stackedTargets.setFrom(b * elementsPerItem, targetRange, 0, elementsPerItem);
			}

			double[] allValues = cosineSimilarityEvaluable(totalFrames, bins)
					.evaluate(stackedQuery, stackedTargets)
					.doubleStream().toArray();

			for (int b = 0; b < actualBatch; b++) {
				WaveDetails target = targets.get(batchStart + b);
				if (target.getFeatureData() == null) {
					results[batchStart + b] = -Double.MAX_VALUE;
					continue;
				}

				results[batchStart + b] = soundingSimilarity(allValues, b * frames, frames,
						query.getValidFeatureFrameCount(), target.getValidFeatureFrameCount());
			}

			batchStart = batchEnd;
		}

		return results;
	}

	/**
	 * Summarizes the per-frame cosine similarities of two items as the mean over
	 * the frames in which either item is sounding, where a frame in which only
	 * one of them is sounding counts as zero similarity. Outliers are removed
	 * with a {@link #trimmedMean trimmed mean} over the frames in which both are
	 * sounding; the one-sided frames are never trimmed, so each of them always
	 * lowers the result.
	 *
	 * <p>Features are computed over a fixed-length clip, so the frames after a
	 * sound ends hold the features of its silent padding rather than of the
	 * sound. Those padding features are not neutral: they are close to the
	 * features of any quiet or decaying frame, so comparing them with the frames
	 * of a longer sound measures nothing about either sound. A short hit and a
	 * drum loop share only the hit's few frames; counting the loop's remaining
	 * frames against the hit's padding made a hit score close to a loop (and a
	 * loop close to every hit). Counting those frames as zero instead makes two
	 * sounds similar only for as long as both are sounding.</p>
	 *
	 * @param cosines per-frame cosine similarities
	 * @param offset  index of the pair's first frame in {@code cosines}
	 * @param frames  number of frames compared for the pair
	 * @param validA  number of sounding frames of the first item
	 * @param validB  number of sounding frames of the second item
	 * @return the similarity of the pair
	 */
	private static double soundingSimilarity(double[] cosines, int offset, int frames, int validA, int validB) {
		int sounding = Math.min(Math.max(validA, validB), frames);
		int shared = Math.min(Math.min(validA, validB), sounding);
		if (sounding == 0) return -1.0;
		if (shared == 0) return 0.0;

		// Outlier trimming applies to the shared frames only, so that the
		// one-sided frames are never trimmed away and each one counts as zero
		double[] values = new double[shared];
		System.arraycopy(cosines, offset, values, 0, shared);
		return trimmedMean(values) * shared / sounding;
	}

	/**
	 * Computes trimmed mean of per-frame similarity values, removing
	 * the bottom 10% and top 2 values to reduce outlier effects.
	 */
	private static double trimmedMean(double[] values) {
		if (values.length == 0) return -1.0;

		int skip = 0;
		int total = values.length;
		if (total > 10) {
			skip = (int) (values.length * 0.1);
			total = total - skip - 2;
		}

		return DoubleStream.of(values).sorted().skip(skip).limit(total).average().orElseThrow();
	}

	/**
	 * Encodes a (frames, bins) pair into a single long key for cache lookup.
	 * Frames occupy the upper 32 bits, bins the lower 32 bits.
	 */
	private static long shapeKey(int frames, int bins) {
		return ((long) frames << 32) | bins;
	}

	/**
	 * Returns a cached evaluable that computes per-frame cosine similarity
	 * between two feature tensors of shape (frames, bins, 1). The evaluable
	 * is optimized and compiled once, then reused for all comparisons with
	 * matching dimensions, so it agrees with an {@code evaluate()} of the same graph.
	 *
	 * <p>Each frame's norm is its magnitude over the bins of that frame
	 * ({@code magnitude(1)}, shape (frames, 1)), matching the per-frame dot
	 * product ({@code sum(1)}), so every value is a true cosine in [-1, 1].</p>
	 */
	private static Evaluable<PackedCollection> cosineSimilarityEvaluable(int frames, int bins) {
		TraversalPolicy shape = new TraversalPolicy(frames, bins, 1);
		return cosineCalc.computeIfAbsent(shapeKey(frames, bins), k ->
				(Evaluable<PackedCollection>) Process.optimized(Ops.op(o -> o.multiply(o.cv(shape, 0), o.cv(shape, 1)).sum(1)
						.divide(o.multiply(o.cv(shape, 0).magnitude(1),
								o.cv(shape, 1).magnitude(1))))).get());
	}

	/**
	 * Aggregates a raw pooled FFT result into scaled frequency bins and stores them in the output.
	 *
	 * @param fft    raw FFT data from {@link WaveData#fft(int, boolean)}, shape (timeSlices, poolBins, 1)
	 * @param output destination collection with shape (count, freqBins, 1)
	 * @return the output collection
	 */
	protected PackedCollection processFft(PackedCollection fft, PackedCollection output) {
		if (fft.getShape().length(0) < 1) {
			throw new IllegalArgumentException();
		}

		int count = output.getShape().length(0);
		TraversalPolicy inShape = shape(scaleTime, WaveData.FFT_POOL_BINS);

		for (int i = 0; i < count; i++) {
			PackedCollection in;

			if (fft.getMemLength() < (i + 1) * inShape.getTotalSize()) {
				buffer.setFrom(0, fft, i * inShape.getTotalSize(), fft.getMemLength() - i * inShape.getTotalSize());
				in = buffer;
			} else {
				in = fft.range(inShape, i * inShape.getTotalSize());
			}

			PackedCollection out = output.range(shape(freqBins, 1), i * freqBins);
			sum.into(out.traverseEach()).evaluate(in.traverseEach());
		}

		return output;
	}

	/**
	 * Computes feature vectors for the given audio provider using the configured feature provider.
	 *
	 * @param provider audio data provider to compute features for
	 * @return PackedCollection of shape (frames, bins) containing feature vectors
	 * @throws IllegalArgumentException if the resulting feature collection has fewer than 1 frame
	 */
	protected PackedCollection prepareFeatures(WaveDataProvider provider) {
		PackedCollection features = featureProvider.computeFeatures(provider);

		if (features.getShape().length(0) < 1) {
			throw new IllegalArgumentException();
		}

		return features;
	}

	/**
	 * Returns the shared default factory, creating it at the default output sample rate if needed.
	 *
	 * @return the default WaveDetailsFactory instance
	 */
	public static WaveDetailsFactory getDefault() {
		if (defaultFactory == null) {
			defaultFactory = new WaveDetailsFactory(OutputLine.sampleRate);
		}

		return defaultFactory;
	}
}
