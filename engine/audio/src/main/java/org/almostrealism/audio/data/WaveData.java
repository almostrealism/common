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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.graph.temporal.WaveCell;
import org.almostrealism.graph.temporal.WaveCellData;
import org.almostrealism.io.Console;

import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Primary container for audio sample data with FFT analysis capabilities.
 *
 * <p>WaveData wraps a {@link PackedCollection} containing audio samples along with
 * metadata like sample rate. It provides methods for loading/saving WAV files,
 * frequency domain analysis via FFT, and conversion to audio cells for processing.</p>
 *
 * <h2>Loading Audio</h2>
 * <pre>{@code
 * // Load from WAV file
 * WaveData data = WaveData.load(new File("audio.wav"));
 *
 * // Access properties
 * int sampleRate = data.getSampleRate();
 * double duration = data.getDuration();
 * PackedCollection samples = data.getCollection();
 * }</pre>
 *
 * <h2>Saving Audio</h2>
 * <pre>{@code
 * // Create from samples
 * PackedCollection samples = new PackedCollection(44100);
 * // ... fill samples ...
 * WaveData data = new WaveData(samples, 44100);
 * data.save(new File("output.wav"));
 * }</pre>
 *
 * <h2>FFT Analysis</h2>
 * <p>WaveData provides frequency domain analysis using {@value #FFT_BINS}-bin FFT:</p>
 * <pre>{@code
 * // Get frequency spectrum at sample position 1000
 * PackedCollection spectrum = data.getFrequencyDomain(1000);
 * }</pre>
 *
 * <h2>Creating Audio Cells</h2>
 * <pre>{@code
 * // Convert to WaveCell for processing
 * WaveCell cell = data.toCell(0, duration, null, c(1.0))
 *     .apply(new DefaultWaveCellData());
 * }</pre>
 *
 * <h2>Constants</h2>
 * <ul>
 *   <li>{@link #FFT_BINS} - Number of FFT bins (1024 for CPU, 256 for GPU)</li>
 *   <li>{@link #enableGpu} - Whether to use GPU acceleration for FFT</li>
 * </ul>
 *
 * @see WaveDataProvider
 * @see org.almostrealism.audio.WavFile
 * @see org.almostrealism.graph.temporal.WaveCell
 */
public class WaveData implements Destroyable, SamplingFeatures, CollectionFeatures {
	/** When true, uses GPU-accelerated FFT (256 bins); when false, uses CPU FFT (1024 bins). */
	public static final boolean enableGpu = false;

	/** Number of FFT frequency bins; 256 when GPU is enabled, 1024 otherwise. */
	public static final int FFT_BINS = enableGpu ? 256 : 1024;

	/** Pooling reduction factor applied to each FFT dimension during 2D pooling. */
	public static final int FFT_POOL = 4;

	/** Number of frequency bins per pool tile after 2D max pooling. */
	public static final int FFT_POOL_BINS = FFT_BINS / FFT_POOL / 2;

	/** Number of frequency bins in a single pooling batch input dimension. */
	public static final int POOL_BATCH_IN = FFT_BINS / 2;

	/** Number of frequency bins in a single pooling batch output dimension. */
	public static final int POOL_BATCH_OUT = POOL_BATCH_IN / FFT_POOL;

	/** When true, emits warnings for unusual conditions such as null wave data. */
	public static boolean enableWarnings = false;

	/** Compiled magnitude computation applied to FFT output to obtain real-valued spectrum. */
	private static final Evaluable<PackedCollection> magnitude;

	/** Compiled FFT kernel operating on interleaved real/imaginary input. */
	private static final Evaluable<PackedCollection> fft;

	/** Compiled 2D max-pooling kernel for compressing FFT output. */
	private static final Evaluable<PackedCollection> pool2d;

	/** Compiled scaled-add operation used for accumulating aggregated FFT results. */
	private static final Evaluable<PackedCollection> scaledAdd;

	static {
		fft = Ops.op(o ->
				o.fft(FFT_BINS, o.v(o.shape(FFT_BINS, 2), 0),
						enableGpu ? ComputeRequirement.GPU : ComputeRequirement.CPU)).get();
		magnitude = Ops.op(o ->
				o.complexFromParts(
						o.v(o.shape(FFT_BINS / 2), 0),
						o.v(o.shape(FFT_BINS / 2), 1)).magnitude()).get();
		pool2d = Ops.op(o ->
				o.c(o.v(o.shape(POOL_BATCH_IN, POOL_BATCH_IN, 1), 0))
						.enumerate(2, 1)
						.enumerate(2, FFT_POOL)
						.enumerate(2, FFT_POOL)
						.traverse(3)
						.max()
						.reshape(POOL_BATCH_OUT, POOL_BATCH_OUT, 1)).get();
		scaledAdd = Ops.op(o -> o.add(o.v(o.shape(-1), 0),
					o.multiply(o.v(o.shape(-1), 1), o.v(o.shape(1), 2))))
				.get();
		// mfcc = new FeatureComputer(getDefaultFeatureSettings());
	}

	/** The underlying audio sample data stored as a PackedCollection. */
	private PackedCollection collection;

	/** The sample rate of this audio data in Hz. */
	private int sampleRate;

	/**
	 * Creates a WaveData with an empty buffer of the specified channel count, frame count, and sample rate.
	 *
	 * @param channels   number of audio channels
	 * @param frames     number of sample frames
	 * @param sampleRate sample rate in Hz
	 */
	public WaveData(int channels, int frames, int sampleRate) {
		this(new PackedCollection(channels, frames), sampleRate);
	}

	/**
	 * Creates a WaveData wrapping an existing PackedCollection at the given sample rate.
	 *
	 * @param wave       the sample data; may be mono (shape [N]) or multi-channel (shape [C, N])
	 * @param sampleRate sample rate in Hz
	 */
	public WaveData(PackedCollection wave, int sampleRate) {
		if (wave == null) {
			System.out.println("WARN: Wave data is null");
		}

		this.collection = wave;
		this.sampleRate = sampleRate;
	}

	/** Returns the raw PackedCollection containing all audio sample data. */
	public PackedCollection getData() { return collection; }

	/**
	 * Returns the sample data for a single channel as a flat, traverseEach-shaped collection.
	 *
	 * @param channel channel index (0-based)
	 * @return slice of the underlying collection for the specified channel
	 * @throws IndexOutOfBoundsException if channel is negative
	 * @throws IllegalArgumentException  if channel exceeds the channel count
	 */
	public PackedCollection getChannelData(int channel) {
		if (channel < 0) {
			throw new IndexOutOfBoundsException();
		} else if (getChannelCount() == 1) {
			return getData().range(shape(getFrameCount()).traverseEach(), 0);
		} else if (channel < getChannelCount()) {
			return getData().range(shape(getFrameCount()).traverseEach(), channel * getFrameCount());
		} else {
			throw new IllegalArgumentException();
		}
	}

	/** Returns the sample rate of this audio data in Hz. */
	public int getSampleRate() { return sampleRate; }

	/**
	 * Sets the sample rate without resampling the underlying data.
	 *
	 * @param sampleRate new sample rate in Hz
	 */
	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	/**
	 * Returns the duration of this audio data in seconds.
	 *
	 * @return duration = frameCount / sampleRate
	 */
	public double getDuration() {
		return getFrameCount() / (double) sampleRate;
	}

	/**
	 * Returns the number of audio channels (1 for mono, 2 for stereo, etc.).
	 *
	 * @return channel count derived from the shape of the underlying collection
	 */
	public int getChannelCount() {
		if (getData().getShape().getDimensions() == 1) {
			return 1;
		}

		return getData().getShape().length(0);
	}

	/**
	 * Returns the number of sample frames per channel.
	 *
	 * @return total frame count
	 */
	public int getFrameCount() {
		if (getData().getShape().getDimensions() == 1) {
			return getData().getMemLength();
		}

		return getData().getShape().length(1);
	}

	/**
	 * Returns a BufferDetails describing this audio data's sample rate and duration.
	 *
	 * @return a BufferDetails with sample rate and duration
	 */
	public BufferDetails getBufferDetails() {
		return new BufferDetails(getSampleRate(), getDuration());
	}

	/**
	 * Returns a new WaveData containing samples from the specified time range.
	 *
	 * @param channel channel index
	 * @param start   start time in seconds
	 * @param length  duration in seconds
	 * @return new WaveData for the specified range
	 */
	public WaveData range(int channel, double start, double length) {
		return range(channel, (int) (start * sampleRate), (int) (length * sampleRate));
	}

	/**
	 * Returns a new WaveData containing samples from the specified frame range.
	 *
	 * @param channel channel index
	 * @param start   start frame index
	 * @param length  number of frames
	 * @return new WaveData for the specified range
	 */
	public WaveData range(int channel, int start, int length) {
		return new WaveData(getChannelData(channel).range(new TraversalPolicy(length), start), sampleRate);
	}

	/**
	 * Applies a factor to each frame of the specified channel and returns a new WaveData.
	 *
	 * @param channel   channel index to process
	 * @param processor factor that maps each frame to a transformed value
	 * @return new WaveData containing the processed samples
	 */
	public WaveData sample(int channel, Factor<PackedCollection> processor) {
		return sample(channel, () -> processor);
	}

	/**
	 * Applies a factor (from a supplier) to each frame of the specified channel and returns a new WaveData.
	 *
	 * @param channel   channel index to process
	 * @param processor supplier of the factor used to transform each frame
	 * @return new WaveData containing the processed samples
	 */
	public WaveData sample(int channel, Supplier<Factor<PackedCollection>> processor) {
		PackedCollection result = new PackedCollection(getChannelData(channel).getShape());
		sampling(getSampleRate(), getDuration(),
					() -> processor.get().getResultant(c(p(getChannelData(channel)), frame())))
				.get().into(result).evaluate();
		return new WaveData(result, getSampleRate());
	}

	/**
	 * Return the fourier transform of this {@link WaveData}.
	 *
	 * @param channel  Audio channel to apply the transform to.
	 * @return  The fourier transform of this {@link WaveData} over time in the
	 *          {@link TraversalPolicy shape} (time slices, bins, 1).
	 */
	public PackedCollection fft(int channel) {
		return fft(channel, false);
	}

	/**
	 * Return the fourier transform of this {@link WaveData}.
	 *
	 * @param channel  Audio channel to apply the transform to.
	 * @param pooling If true, the fourier transform will be pooled to reduce its size
	 *                by {@value FFT_POOL} in each dimension.
	 * @return The fourier transform of this {@link WaveData} over time in the
	 * {@link TraversalPolicy shape} (time slices, bins, 1).
	 */
	public PackedCollection fft(int channel, boolean pooling) {
		return fft(channel, pooling, false, false);
	}

	/**
	 * Return the aggregated fourier transform of this {@link WaveData}.
	 *
	 * @param includeAll  If true, all {@value WaveData#FFT_BINS} bins of transform data
	 *                    will be included in the result.  If false, only the first half
	 *                    of the bins will be included.
	 * @return  The aggregated fourier transform of this {@link WaveData} in the
	 *          {@link TraversalPolicy shape} (bins, 1).
	 */
	public PackedCollection aggregatedFft(boolean includeAll) {
		return fft(-1, false, true, includeAll);
	}

	/**
	 * Core FFT implementation shared by all public FFT methods.
	 *
	 * @param channel    channel index, or -1 to aggregate all channels
	 * @param pooling    if true, applies 2D max pooling to reduce resolution
	 * @param sum        if true, aggregates time slices into a single spectrum
	 * @param includeAll if true, returns all FFT_BINS; otherwise returns only the first half
	 * @return PackedCollection containing the FFT result in the appropriate shape
	 */
	protected PackedCollection fft(int channel, boolean pooling, boolean sum, boolean includeAll) {
		PackedCollection inRoot = new PackedCollection(FFT_BINS * FFT_BINS);
		PackedCollection outRoot = new PackedCollection(POOL_BATCH_OUT * POOL_BATCH_OUT);

		int count = getFrameCount() > FFT_BINS ? getFrameCount() / FFT_BINS : 1;
		int finalBins = includeAll ? FFT_BINS : (FFT_BINS / 2);
		PackedCollection out =
				new PackedCollection(count * finalBins)
				.reshape(count, finalBins, 1);

		try {
			if (enableGpu && count > 1) {
				PackedCollection frameIn = getChannelData(channel).range(shape(count, FFT_BINS, 2));
				PackedCollection frameOut = new PackedCollection(shape(count, FFT_BINS, 2));

				fft.into(frameOut.traverse()).evaluate(frameIn.traverse());

				for (int i = 0; i < count; i++) {
					int offset = i * FFT_BINS;

					magnitude
							.into(out.range(shape(finalBins, 1), i * finalBins).traverseEach())
							.evaluate(
									frameOut.range(shape(finalBins), offset).traverseEach(),
									frameOut.range(shape(finalBins), offset + finalBins).traverseEach());
				}
			} else {
				PackedCollection frameIn = inRoot.range(shape(FFT_BINS, 2));
				PackedCollection frameOut = outRoot.range(shape(FFT_BINS, 2));

				for (int i = 0; i < count; i++) {
					frameIn.setMem(0, getChannelData(channel), i * FFT_BINS,
							Math.min(FFT_BINS, getFrameCount()- i * FFT_BINS));
					fft.into(frameOut).evaluate(frameIn);

					// TODO This may not work correctly when finalBins != FFT_BINS / 2
					magnitude
							.into(out.range(shape(finalBins, 1), i * finalBins).traverseEach())
							.evaluate(
									frameOut.range(shape(finalBins), 0).traverseEach(),
									frameOut.range(shape(finalBins), finalBins).traverseEach());
				}
			}

			if (pooling) {
				if (sum) {
					// TODO  This should also be supported
					throw new UnsupportedOperationException();
				}

				int resultSize = count / FFT_POOL;
				if (count % FFT_POOL != 0) resultSize++;

				PackedCollection pool = PackedCollection.factory().apply(resultSize * FFT_POOL_BINS)
						.reshape(resultSize, FFT_POOL_BINS, 1);

				int window = POOL_BATCH_IN * POOL_BATCH_IN;
				int poolWindow = POOL_BATCH_OUT * POOL_BATCH_OUT;
				int pcount = resultSize / POOL_BATCH_OUT;
				if (resultSize % POOL_BATCH_OUT != 0) pcount++;

				PackedCollection poolIn = inRoot.range(shape(POOL_BATCH_IN, POOL_BATCH_IN, 1));
				PackedCollection poolOut = outRoot.range(shape(POOL_BATCH_OUT, POOL_BATCH_OUT, 1));

				if (pcount < 1) {
					throw new IllegalArgumentException();
				}

				for (int i = 0; i < pcount; i++) {
					int remaining = out.getMemLength() - i * window;
					poolIn.setMem(0, out, i * window, Math.min(window, remaining));

					pool2d.into(poolOut.traverseEach()).evaluate(poolIn.traverseEach());

					remaining = pool.getMemLength() - i * poolWindow;
					pool.setMem(i * poolWindow, poolOut, 0, Math.min(poolWindow, remaining));
				}

				return pool.range(shape(resultSize, FFT_POOL_BINS, 1));
			} else if (sum) {
				PackedCollection sumOut = new PackedCollection(finalBins, 1);

				for (int i = 0; i < count; i++) {
					scaledAdd.into(sumOut.each()).evaluate(
							sumOut.each(),
							out.range(shape(finalBins, 1), i * finalBins).each(),
							pack(1.0 / count));
				}

				return sumOut;
			} else {
				return out;
			}
		} finally {
			inRoot.destroy();
			outRoot.destroy();
			if (pooling || sum) out.destroy();
		}
	}

	/**
	 * Saves this audio data to a 24-bit stereo WAV file.
	 *
	 * @param file destination file to write
	 * @return true if the file was written successfully; false if there are no frames or an I/O error occurs
	 */
	public boolean save(File file) {
		if (getFrameCount() <= 0) {
			Console.root.features(WavFile.class).log("No frames to write");
			return false;
		}

		int frameCount = getFrameCount();

		try (WavFile wav = WavFile.newWavFile(file, 2, frameCount, 24, getSampleRate())) {
			double[] leftFrames = getChannelData(0).toArray(0, frameCount);
			double[] rightFrames = getChannelData(1).toArray(0, frameCount);

			for (int i = 0; i < frameCount; i++) {
				double l = leftFrames[i];
				double r = rightFrames[i];

				try {
					wav.writeFrames(new double[][]{{l}, {r}}, 1);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Converts this WaveData to a WaveCell using the clock's frame position, on channel 0.
	 *
	 * @param clock the TimeCell providing the frame position producer
	 * @return a WaveCell for channel 0 driven by the clock
	 */
	public WaveCell toCell(TimeCell clock) {
		return toCell(clock, 0);
	}

	/**
	 * Converts the specified channel of this WaveData to a WaveCell driven by the given clock.
	 *
	 * @param clock   the TimeCell providing the frame position producer
	 * @param channel channel index
	 * @return a WaveCell for the specified channel
	 */
	public WaveCell toCell(TimeCell clock, int channel) {
		return toCell(clock.frame(), channel);
	}

	/**
	 * Converts the specified channel of this WaveData to a WaveCell driven by a frame position producer.
	 *
	 * @param frame   producer supplying the current frame index
	 * @param channel channel index
	 * @return a WaveCell reading from the specified channel
	 */
	public WaveCell toCell(Producer<PackedCollection> frame, int channel) {
		return new WaveCell(getChannelData(channel), frame);
	}

	/**
	 * Returns a factory function that creates a WaveCell from a WaveCellData for the specified channel.
	 *
	 * @param channel   channel index
	 * @param amplitude playback amplitude scaling factor
	 * @param offset    optional producer for a playback offset in frames; null for no offset
	 * @param repeat    optional producer for the repeat frame count; null for no repeat
	 * @return function mapping WaveCellData to a configured WaveCell
	 */
	public Function<WaveCellData, WaveCell> toCell(int channel, double amplitude,
												   Producer<PackedCollection> offset,
												   Producer<PackedCollection> repeat) {
		return data -> new WaveCell(data, getChannelData(channel),
									getSampleRate(), amplitude,
									offset == null ? null : Ops.o().c(offset),
									repeat == null ? null : Ops.o().c(repeat),
									Ops.o().c(0.0), Ops.o().c(getFrameCount()));
	}

	/**
	 * Generates a spectrogram image from this audio data.
	 *
	 * <p>The spectrogram shows frequency content over time, with:</p>
	 * <ul>
	 *   <li>X-axis: time (left to right)</li>
	 *   <li>Y-axis: frequency (low at bottom, high at top)</li>
	 *   <li>Brightness: magnitude (log-scaled for better visibility)</li>
	 * </ul>
	 *
	 * <p>The returned collection has shape (height, width, 3) suitable for
	 * saving as an RGB image. All computation is hardware-accelerated.</p>
	 *
	 * @param channel the audio channel to analyze
	 * @return PackedCollection with shape (bins, timeSlices, 3) containing RGB data
	 */
	public PackedCollection spectrogram(int channel) {
		return spectrogram(channel, true);
	}

	/**
	 * Generates a spectrogram image from this audio data.
	 *
	 * @param channel the audio channel to analyze
	 * @param pooling if true, reduces resolution for faster processing
	 * @return PackedCollection with shape (bins, timeSlices, 3) containing RGB data
	 */
	public PackedCollection spectrogram(int channel, boolean pooling) {
		PackedCollection spectrum = fft(channel, pooling);

		int timeSlices = spectrum.getShape().length(0);
		int bins = spectrum.getShape().length(1);

		// Find max value using hardware-accelerated max operation
		double maxVal = cp(spectrum).max().evaluate().toDouble(0);

		if (maxVal <= 0) {
			// Return black image if no signal
			return new PackedCollection(bins, timeSlices, 3);
		}

		// Compute log(1 + maxVal) for normalization denominator
		double logMaxPlusOne = Math.log1p(maxVal);

		// Transpose spectrum from (time, bins, 1) to (bins, time) for proper memory layout
		// and flip vertically (low frequencies at bottom = high Y index)
		PackedCollection transposed = cp(spectrum).reshape(timeSlices, bins).transpose().evaluate();
		// transposed now has shape (bins, timeSlices) with frequency bins as rows

		// Normalize: log(1 + x) / log(1 + max) for all values
		CollectionProducer normalized = c(1.0).add(cp(transposed)).log().divide(c(logMaxPlusOne));
		PackedCollection normalizedData = normalized.evaluate();

		// Flip vertically: row 0 becomes row (bins-1), etc.
		PackedCollection flipped = new PackedCollection(bins, timeSlices);
		for (int b = 0; b < bins; b++) {
			int srcBin = bins - 1 - b;  // Flip: low frequencies at bottom
			flipped.setMem(b * timeSlices, normalizedData, srcBin * timeSlices, timeSlices);
		}

		// Create RGB image by repeating grayscale values across 3 channels
		// Output shape: (bins, timeSlices, 3)
		PackedCollection image = new PackedCollection(bins, timeSlices, 3);

		// Use hardware-accelerated repeat operation
		// Reshape flipped to (bins * timeSlices, 1) and repeat 3x for RGB channels
		CollectionProducer gray = cp(flipped).reshape(bins * timeSlices, 1);
		CollectionProducer rgb = gray.repeat(3).reshape(bins, timeSlices, 3);

		rgb.into(image).evaluate();

		// Clean up intermediate collections
		transposed.destroy();
		normalizedData.destroy();
		flipped.destroy();

		return image;
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		if (collection != null) collection.destroy();
		collection = null;
	}

	/** Forces static initialization of FFT kernels. Call once during application startup. */
	public static void init() { }

	/**
	 * Loads a WAV file from disk and returns its data as a WaveData instance.
	 *
	 * @param f WAV file to load
	 * @return WaveData containing the decoded audio data and sample rate
	 * @throws IOException if the file cannot be read or is not a valid WAV file
	 */
	public static WaveData load(File f) throws IOException {
		try (WavFile w = WavFile.openWavFile(f)) {
			int channelCount = w.getNumChannels();
			PackedCollection in = new PackedCollection(new TraversalPolicy(channelCount, w.getNumFrames()));

			double[][] wave = new double[w.getNumChannels()][(int) w.getFramesRemaining()];
			w.readFrames(wave, 0, (int) w.getFramesRemaining());

			for (int c = 0; c < wave.length; c++) {
				in.setMem(Math.toIntExact(c * w.getNumFrames()), wave[c]);
			}

			return new WaveData(in, (int) w.getSampleRate());
		}
	}
}
