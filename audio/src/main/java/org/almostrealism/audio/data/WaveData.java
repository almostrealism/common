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

public class WaveData implements Destroyable, SamplingFeatures {
	public static final boolean enableGpu = false;

	public static final int FFT_BINS = enableGpu ? 256 : 1024;
	public static final int FFT_POOL = 4;
	public static final int FFT_POOL_BINS = FFT_BINS / FFT_POOL / 2;

	public static final int POOL_BATCH_IN = FFT_BINS / 2;
	public static final int POOL_BATCH_OUT = POOL_BATCH_IN / FFT_POOL;

	public static boolean enableWarnings = false;

	private static final Evaluable<PackedCollection> magnitude;
	private static final Evaluable<PackedCollection> fft;
	private static final Evaluable<PackedCollection> pool2d;
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

	private PackedCollection collection;
	private int sampleRate;

	public WaveData(int channels, int frames, int sampleRate) {
		this(new PackedCollection(channels, frames), sampleRate);
	}

	public WaveData(PackedCollection wave, int sampleRate) {
		if (wave == null) {
			System.out.println("WARN: Wave data is null");
		}

		this.collection = wave;
		this.sampleRate = sampleRate;
	}

	public PackedCollection getData() { return collection; }

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

	public int getSampleRate() { return sampleRate; }
	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	public double getDuration() {
		return getFrameCount() / (double) sampleRate;
	}

	public int getChannelCount() {
		if (getData().getShape().getDimensions() == 1) {
			return 1;
		}

		return getData().getShape().length(0);
	}

	public int getFrameCount() {
		if (getData().getShape().getDimensions() == 1) {
			return getData().getMemLength();
		}

		return getData().getShape().length(1);
	}

	public BufferDetails getBufferDetails() {
		return new BufferDetails(getSampleRate(), getDuration());
	}

	public WaveData range(int channel, double start, double length) {
		return range(channel, (int) (start * sampleRate), (int) (length * sampleRate));
	}

	public WaveData range(int channel, int start, int length) {
		return new WaveData(getChannelData(channel).range(new TraversalPolicy(length), start), sampleRate);
	}

	public WaveData sample(int channel, Factor<PackedCollection> processor) {
		return sample(channel, () -> processor);
	}

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

	public WaveCell toCell(TimeCell clock) {
		return toCell(clock, 0);
	}

	public WaveCell toCell(TimeCell clock, int channel) {
		return toCell(clock.frame(), channel);
	}

	public WaveCell toCell(Producer<PackedCollection> frame, int channel) {
		return new WaveCell(getChannelData(channel), frame);
	}

	public Function<WaveCellData, WaveCell> toCell(int channel, double amplitude,
												   Producer<PackedCollection> offset,
												   Producer<PackedCollection> repeat) {
		return data -> new WaveCell(data, getChannelData(channel),
									getSampleRate(), amplitude,
									offset == null ? null : Ops.o().c(offset),
									repeat == null ? null : Ops.o().c(repeat),
									Ops.o().c(0.0), Ops.o().c(getFrameCount()));
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		if (collection != null) collection.destroy();
		collection = null;
	}

	public static void init() { }

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
