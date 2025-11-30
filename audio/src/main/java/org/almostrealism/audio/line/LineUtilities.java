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

package org.almostrealism.audio.line;

import org.almostrealism.collect.PackedCollection;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class LineUtilities {
	protected static AudioFormat lastFormat;
	
	/**
	 * Returns a SourceDataOutputLine for the most recent format requested.
	 */
	public static OutputLine getLine() {
		if (lastFormat == null) {
			lastFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, OutputLine.sampleRate,
					16, 2, 4,
					OutputLine.sampleRate, false);
		}

		return getLine(lastFormat);
	}

	public static int frameCount(PackedCollection sample) {
		if (sample.getShape().getDimensions() == 1) {
			return sample.getShape().length(0);
		} else {
			return sample.getShape().length(1);
		}
	}
	
	/**
	 * Returns a SourceDataOutputLine for the specified format.
	 */
	public static OutputLine getLine(AudioFormat format) {
		SourceDataLine line;
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		if (!AudioSystem.isLineSupported(info)) {
			System.out.println("Not supported");
			return null;
		}
		
		lastFormat = format;
		
		try {
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format);
			line.start();
		} catch (LineUnavailableException ex) {
			System.out.println("Unavailable (" + ex.getMessage() + ")");
			return null;
		}
		
		return new SourceDataOutputLine(line);
	}

	/**
	 * Converts multi-channel audio frames to bytes in the specified format.
	 * The input array format is frames[channel][sample].
	 *
	 * @param frames 2D array where first dimension is channel, second is sample
	 * @param format The target audio format
	 * @return Byte array containing interleaved audio frames
	 */
	public static byte[] toBytes(double[][] frames, AudioFormat format) {
		if (frames.length == 0 || frames[0].length == 0) {
			return new byte[0];
		}

		int channels = Math.min(frames.length, format.getChannels());
		int sampleCount = frames[0].length;
		int frameSize = format.getFrameSize();
		int bytesPerSample = frameSize / format.getChannels();

		ByteBuffer buf = ByteBuffer.allocate(sampleCount * frameSize);

		if (!format.isBigEndian()) {
			buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		}

		int bitRate = format.getSampleSizeInBits();
		double floatOffset;
		double floatScale;

		if (format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
			floatOffset = 0;
			floatScale = (1L << (bitRate - 1)) - 1; // e.g., 32767 for 16-bit
		} else if (format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED) {
			floatOffset = 1;
			floatScale = 0.5 * ((1L << bitRate) - 1);
		} else {
			throw new UnsupportedOperationException("Encoding " + format.getEncoding() + " is not supported");
		}

		// Interleave channels: for each frame, write all channels
		for (int s = 0; s < sampleCount; s++) {
			for (int c = 0; c < format.getChannels(); c++) {
				// Use channel data if available, otherwise use last available channel (or 0)
				double sample = (c < channels) ? frames[c][s] : (channels > 0 ? frames[channels - 1][s] : 0.0);
				// Clamp to [-1.0, 1.0] range
				sample = Math.max(-1.0, Math.min(1.0, sample));

				long val = (long) (floatScale * (floatOffset + sample));

				// Write the sample in the appropriate format
				if (bytesPerSample == 1) {
					buf.put((byte) val);
				} else if (bytesPerSample == 2) {
					buf.putShort((short) val);
				} else if (bytesPerSample == 3) {
					// 24-bit audio
					if (format.isBigEndian()) {
						buf.put((byte) ((val >> 16) & 0xFF));
						buf.put((byte) ((val >> 8) & 0xFF));
						buf.put((byte) (val & 0xFF));
					} else {
						buf.put((byte) (val & 0xFF));
						buf.put((byte) ((val >> 8) & 0xFF));
						buf.put((byte) ((val >> 16) & 0xFF));
					}
				} else if (bytesPerSample == 4) {
					buf.putInt((int) val);
				} else {
					throw new UnsupportedOperationException("Sample size " + bytesPerSample + " bytes is not supported");
				}
			}
		}

		return buf.array();
	}

	/**
	 * Converts the specified PackedCollection to bytes representing audio frames
	 * in the format specified by the AudioFormat.
	 * <p>
	 * The PackedCollection shape is interpreted as:
	 * - 1D (length): Mono audio with 'length' frames
	 * - 2D (channels, frames): Multi-channel audio
	 * <p>
	 * If the input is mono and the format expects stereo, the mono channel is duplicated.
	 * If the input has more than 2 channels and format expects stereo, the first 2 channels are used.
	 *
	 * @param samples The audio samples as a PackedCollection
	 * @param format The target audio format
	 * @return Byte array containing the encoded audio frames
	 */
	public static byte[] toFrame(PackedCollection samples, AudioFormat format) {
		// Determine the shape of the input
		int inputChannels;
		int frameCount;

		if (samples.getShape().getDimensions() == 1) {
			// 1D collection: mono audio
			inputChannels = 1;
			frameCount = samples.getShape().length(0);
		} else {
			// 2D collection: (channels, frames)
			inputChannels = samples.getShape().length(0);
			frameCount = samples.getShape().length(1);
		}

		int outputChannels = format.getChannels();
		int frameSize = format.getFrameSize();
		int bytesPerSample = frameSize / outputChannels;

		byte[] frameBytes = new byte[frameCount * frameSize];
		ByteBuffer buf = ByteBuffer.wrap(frameBytes);

		if (!format.isBigEndian()) {
			buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		}

		int bitRate = format.getSampleSizeInBits();
		double floatOffset;
		double floatScale;

		if (format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
			floatOffset = 0;
			floatScale = (1L << (bitRate - 1)) - 1; // e.g., 32767 for 16-bit
		} else if (format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED) {
			floatOffset = 1;
			floatScale = 0.5 * ((1L << bitRate) - 1);
		} else {
			throw new UnsupportedOperationException("Encoding " + format.getEncoding() + " is not supported");
		}

		// Write frames
		for (int frame = 0; frame < frameCount; frame++) {
			// Write each output channel
			for (int outChan = 0; outChan < outputChannels; outChan++) {
				double sample;

				if (inputChannels == 1) {
					// Mono input: duplicate to all output channels
					// For 1D: index is just frame
					sample = samples.toDouble(frame);
				} else {
					// Multi-channel input: use flat index (channel * frameCount + frame)
					int channel = Math.min(outChan, inputChannels - 1);
					int flatIndex = channel * frameCount + frame;
					sample = samples.toDouble(flatIndex);
				}

				// Clamp to [-1.0, 1.0] range
				sample = Math.max(-1.0, Math.min(1.0, sample));

				long value = (long) (floatScale * (floatOffset + sample));

				// Write the sample in the appropriate format
				if (bytesPerSample == 1) {
					buf.put((byte) value);
				} else if (bytesPerSample == 2) {
					buf.putShort((short) value);
				} else if (bytesPerSample == 3) {
					// 24-bit audio (3 bytes per sample)
					if (format.isBigEndian()) {
						buf.put((byte) ((value >> 16) & 0xFF));
						buf.put((byte) ((value >> 8) & 0xFF));
						buf.put((byte) (value & 0xFF));
					} else {
						buf.put((byte) (value & 0xFF));
						buf.put((byte) ((value >> 8) & 0xFF));
						buf.put((byte) ((value >> 16) & 0xFF));
					}
				} else if (bytesPerSample == 4) {
					buf.putInt((int) value);
				} else {
					throw new UnsupportedOperationException("Sample size " + bytesPerSample + " bytes is not supported");
				}
			}
		}

		return frameBytes;
	}
	
	public static AudioFormat getAudioFormat() {
		return lastFormat;
	}
	
	/**
	 * Initializes the default audio format using that data read from the specified stream.
	 * This method buffers the stream for you.
	 * 
	 * @param instream  Stream to read initial audio data from.
	 * @throws UnsupportedAudioFileException
	 * @throws IOException
	 * @return  The format of the data that was read.
	 */
	public static AudioFormat initDefaultAudioFormat(InputStream instream) throws UnsupportedAudioFileException, IOException {
		instream = new BufferedInputStream(instream);
		AudioInputStream in = AudioSystem.getAudioInputStream(instream);
		return lastFormat = in.getFormat();
	}
}
