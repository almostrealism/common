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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Characterization tests for the PCM sample encoding performed by
 * {@link LineUtilities#toBytes(double[][], AudioFormat)} and
 * {@link LineUtilities#toFrame(PackedCollection, AudioFormat)}.
 *
 * <p>These pin the byte-level encoding behavior (scale/offset selection,
 * range clamping, per-sample byte layout across bit depths and endianness,
 * and mono-to-multichannel duplication) so that consolidating the shared
 * encoding logic between the two methods provably preserves it.</p>
 */
public class LineUtilitiesEncodingTest extends TestSuiteBase {

	/**
	 * Builds a signed PCM format with the given bit depth, channel count and endianness.
	 *
	 * @param bits      the sample size in bits
	 * @param channels  the number of channels
	 * @param bigEndian whether samples are big-endian
	 * @return the corresponding signed PCM {@link AudioFormat}
	 */
	private static AudioFormat signed(int bits, int channels, boolean bigEndian) {
		int frameSize = channels * (bits / 8);
		return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, bits,
				channels, frameSize, 44100f, bigEndian);
	}

	/**
	 * Builds an 8-bit unsigned PCM format with the given channel count.
	 *
	 * @param channels the number of channels
	 * @return the corresponding unsigned PCM {@link AudioFormat}
	 */
	private static AudioFormat unsigned8(int channels) {
		return new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 44100f, 8,
				channels, channels, 44100f, false);
	}

	/**
	 * Full-scale positive and negative samples encode to the signed 16-bit
	 * extremes, and out-of-range samples are clamped to {@code [-1, 1]} first.
	 */
	@Test(timeout = 30000)
	public void toBytesEncodesSigned16BitFullScaleAndClamps() {
		AudioFormat format = signed(16, 1, false);
		double[][] frames = {{1.0, -1.0, 0.0, 2.0, -3.0}};

		byte[] bytes = LineUtilities.toBytes(frames, format);
		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

		assertEquals(32767, buf.getShort(0));
		assertEquals(-32767, buf.getShort(2));
		assertEquals(0, buf.getShort(4));
		assertEquals(32767, buf.getShort(6));
		assertEquals(-32767, buf.getShort(8));
	}

	/**
	 * A 24-bit full-scale sample is written high-byte first when big-endian
	 * and low-byte first when little-endian.
	 */
	@Test(timeout = 30000)
	public void toBytes24BitEndianness() {
		double[][] frames = {{1.0}};

		byte[] be = LineUtilities.toBytes(frames, signed(24, 1, true));
		assertArrayEquals(new byte[] {(byte) 0x7F, (byte) 0xFF, (byte) 0xFF}, be);

		byte[] le = LineUtilities.toBytes(frames, signed(24, 1, false));
		assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0x7F}, le);
	}

	/**
	 * Unsigned 8-bit PCM maps silence to the mid-point and the extremes to
	 * the full unsigned range.
	 */
	@Test(timeout = 30000)
	public void toBytesUnsigned8Bit() {
		byte[] bytes = LineUtilities.toBytes(new double[][] {{0.0, 1.0, -1.0}}, unsigned8(1));

		assertEquals(127, bytes[0] & 0xFF);
		assertEquals(255, bytes[1] & 0xFF);
		assertEquals(0, bytes[2] & 0xFF);
	}

	/**
	 * A mono {@link PackedCollection} is duplicated to every output channel
	 * when encoded through {@link LineUtilities#toFrame(PackedCollection, AudioFormat)}.
	 */
	@Test(timeout = 30000)
	public void toFrameDuplicatesMonoToStereo() {
		AudioFormat format = signed(16, 2, false);

		PackedCollection samples = new PackedCollection(3);
		samples.fill(1.0, 0.5, -1.0);

		byte[] bytes = LineUtilities.toFrame(samples, format);
		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

		assertEquals(32767, buf.getShort(0));
		assertEquals(32767, buf.getShort(2));
		assertEquals(16383, buf.getShort(4));
		assertEquals(16383, buf.getShort(6));
		assertEquals(-32767, buf.getShort(8));
		assertEquals(-32767, buf.getShort(10));
	}

	/**
	 * The array-based {@link LineUtilities#toBytes(double[][], AudioFormat)} and the
	 * collection-based {@link LineUtilities#toFrame(PackedCollection, AudioFormat)} must
	 * produce identical bytes for equivalent multi-channel input, which is the property
	 * that makes their shared encoding logic safe to consolidate.
	 */
	@Test(timeout = 30000)
	public void toBytesAndToFrameAgreeForStereo16Bit() {
		AudioFormat format = signed(16, 2, false);
		double[][] frames = {{0.25, -0.5, 0.75}, {-0.25, 1.0, -1.0}};

		byte[] fromBytes = LineUtilities.toBytes(frames, format);

		PackedCollection samples = new PackedCollection(2, 3);
		samples.fill(0.25, -0.5, 0.75, -0.25, 1.0, -1.0);
		byte[] fromFrame = LineUtilities.toFrame(samples, format);

		assertArrayEquals(fromBytes, fromFrame);
	}
}
