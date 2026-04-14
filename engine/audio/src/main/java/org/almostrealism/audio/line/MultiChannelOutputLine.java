/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.line;

import org.almostrealism.collect.PackedCollection;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * An {@link OutputLine} that writes to a multi-channel {@link SourceDataLine},
 * routing different audio sources to specific stereo output pairs.
 *
 * <p>Each pair has its own {@link PackedCollection} source buffer of shape
 * {@code (bufferSize)}. The caller (typically a cell pipeline with per-group
 * {@link org.almostrealism.audio.WaveOutput} writers) fills these buffers
 * during the tick cycle. On each {@link #write(PackedCollection)} call, this
 * class interleaves all pair buffers into a complete multi-channel frame
 * and writes to the hardware.</p>
 *
 * @see ChannelPairView
 */
public class MultiChannelOutputLine implements OutputLine {

	/** The underlying multi-channel Java Sound API line. */
	private SourceDataLine line;

	/** Total output channels (e.g., 8 for 4 stereo pairs). */
	private final int outputChannels;

	/** Number of stereo pairs available. */
	private final int pairCount;

	/** Buffer size in frames. */
	private final int bufferSize;

	/** Audio format of the underlying line. */
	private final AudioFormat format;

	/**
	 * Per-pair source buffers. These are filled by the pipeline during the
	 * tick cycle (typically via {@link org.almostrealism.audio.WaveOutput}
	 * writers attached to each output group's SummationCell). Null until
	 * set via {@link #setPairSource(int, PackedCollection)}.
	 */
	private final PackedCollection[] pairSources;

	/** When true, the line is being reset. */
	private volatile boolean resetting;

	/**
	 * Creates a new multi-channel output line.
	 *
	 * @param line           the opened multi-channel SourceDataLine
	 * @param outputChannels the number of output channels (must be even)
	 * @param bufferSize     the buffer size in frames per pair
	 */
	public MultiChannelOutputLine(SourceDataLine line, int outputChannels,
								  int bufferSize) {
		this.line = line;
		this.outputChannels = outputChannels;
		this.pairCount = outputChannels / 2;
		this.bufferSize = bufferSize;
		this.format = line.getFormat();
		this.pairSources = new PackedCollection[pairCount];
	}

	/**
	 * Sets the source buffer for the specified pair. The buffer must have
	 * at least {@link #getBufferSize()} elements. During {@link #write},
	 * values from this buffer are placed into the multi-channel output
	 * at channel positions {@code pairIndex*2} and {@code pairIndex*2+1}.
	 *
	 * @param pairIndex the 0-based pair index
	 * @param source    the source buffer (1D, at least bufferSize elements)
	 */
	public void setPairSource(int pairIndex, PackedCollection source) {
		if (pairIndex < 0 || pairIndex >= pairCount) {
			throw new IllegalArgumentException(
					"Pair index " + pairIndex + " out of range");
		}
		pairSources[pairIndex] = source;
	}

	/**
	 * Returns a {@link ChannelPairView} for the specified stereo output pair.
	 *
	 * @param pairIndex 0-based pair index
	 * @return the channel pair view
	 */
	public ChannelPairView getView(int pairIndex) {
		if (pairIndex < 0 || pairIndex >= pairCount) {
			throw new IllegalArgumentException(
					"Pair index " + pairIndex + " out of range [0, " + pairCount + ")");
		}
		return new ChannelPairView(this, pairIndex);
	}

	/** Returns the number of stereo output pairs available. */
	public int getPairCount() {
		return pairCount;
	}

	/** Returns the total number of output channels. */
	public int getOutputChannels() {
		return outputChannels;
	}

	/**
	 * Interleaves all pair source buffers into a complete multi-channel
	 * frame and writes it to the hardware. Pairs without a source buffer
	 * contribute silence to their channel positions.
	 */
	@Override
	public void write(PackedCollection sample) {
		if (resetting || line == null) return;

		int frames = LineUtilities.frameCount(sample);
		if (frames > bufferSize) frames = bufferSize;

		int frameSize = format.getFrameSize();
		int bytesPerSample = frameSize / outputChannels;
		int bitRate = format.getSampleSizeInBits();
		double floatScale = (1L << (bitRate - 1)) - 1;

		byte[] bytes = new byte[frames * frameSize];
		ByteBuffer buf = ByteBuffer.wrap(bytes);
		if (!format.isBigEndian()) {
			buf.order(ByteOrder.LITTLE_ENDIAN);
		}

		for (int f = 0; f < frames; f++) {
			for (int ch = 0; ch < outputChannels; ch++) {
				int pair = ch / 2;
				double s = 0.0;
				PackedCollection src = pairSources[pair];
				if (src != null && f < src.getShape().getTotalSize()) {
					s = src.toDouble(f);
				}
				s = Math.max(-1.0, Math.min(1.0, s));
				long val = (long) (floatScale * s);

				if (bytesPerSample == 2) {
					buf.putShort((short) val);
				} else if (bytesPerSample == 1) {
					buf.put((byte) val);
				} else if (bytesPerSample == 4) {
					buf.putInt((int) val);
				}
			}
		}

		line.write(bytes, 0, bytes.length);
	}

	@Override
	public int getReadPosition() {
		if (line == null) return 0;
		long framePosition = line.getLongFramePosition();
		return (int) (framePosition % bufferSize);
	}

	@Override
	public int getBufferSize() {
		return bufferSize;
	}

	@Override
	public void start() {
		if (line != null && !line.isActive()) {
			line.start();
		}
	}

	@Override
	public void stop() {
		if (line != null && line.isActive()) {
			line.stop();
		}
	}

	@Override
	public boolean isActive() {
		return line != null && line.isActive();
	}

	@Override
	public synchronized void reset() {
		resetting = true;
		resetting = false;
	}

	@Override
	public void destroy() {
		if (line != null) {
			if (line.isActive()) {
				line.drain();
				line.stop();
			}
			if (line.isOpen()) {
				line.close();
			}
			line = null;
		}
	}
}
