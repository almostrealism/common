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

package org.almostrealism.hardware.test;

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.mem.ByteBufferTransfer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tests for {@link ByteBufferTransfer}: precision conversion in both
 * directions, bulk same-precision movement, byte-order correctness, and
 * interleaving multiple sources into one destination via
 * {@link ByteBufferTransfer#copyNext()}.
 */
public class ByteBufferTransferTest extends TestSuiteBase {

	/**
	 * FP64 values narrow to FP32 across the transfer, advancing both positions.
	 */
	@Test(timeout = 60000)
	public void narrowingConversion() {
		ByteBuffer source = ByteBuffer.allocate(3 * 8).order(ByteOrder.nativeOrder());
		source.asDoubleBuffer().put(new double[] { 1.25, -2.5, 3.75 });
		ByteBuffer destination = ByteBuffer.allocate(3 * 4).order(ByteOrder.nativeOrder());

		new ByteBufferTransfer(source, Precision.FP64,
				destination, Precision.FP32).copyAll();

		Assert.assertEquals(0, source.remaining());
		Assert.assertEquals(0, destination.remaining());
		Assert.assertEquals(1.25f, destination.getFloat(0), 0.0f);
		Assert.assertEquals(-2.5f, destination.getFloat(4), 0.0f);
		Assert.assertEquals(3.75f, destination.getFloat(8), 0.0f);
	}

	/**
	 * FP32 values widen to FP64 across the transfer.
	 */
	@Test(timeout = 60000)
	public void wideningConversion() {
		ByteBuffer source = ByteBuffer.allocate(2 * 4).order(ByteOrder.nativeOrder());
		source.asFloatBuffer().put(new float[] { 7.5f, -0.125f });
		ByteBuffer destination = ByteBuffer.allocate(2 * 8).order(ByteOrder.nativeOrder());

		new ByteBufferTransfer(source, Precision.FP32,
				destination, Precision.FP64).copyAll();

		Assert.assertEquals(7.5, destination.getDouble(0), 0.0);
		Assert.assertEquals(-0.125, destination.getDouble(8), 0.0);
	}

	/**
	 * Same precision and byte order moves values exactly, in bulk.
	 */
	@Test(timeout = 60000)
	public void samePrecisionBulk() {
		double values[] = { 0.1, 0.2, 0.3, 0.4 };
		ByteBuffer source = ByteBuffer.allocate(4 * 8).order(ByteOrder.nativeOrder());
		source.asDoubleBuffer().put(values);
		ByteBuffer destination = ByteBuffer.allocate(4 * 8).order(ByteOrder.nativeOrder());

		new ByteBufferTransfer(source, Precision.FP64,
				destination, Precision.FP64).copyAll();

		Assert.assertEquals(0, source.remaining());
		for (int i = 0; i < 4; i++) {
			Assert.assertEquals(values[i], destination.getDouble(i * 8), 0.0);
		}
	}

	/**
	 * A transfer between buffers of different byte orders converts each
	 * value through its buffer's own order, so values survive intact.
	 */
	@Test(timeout = 60000)
	public void crossByteOrder() {
		ByteBuffer source = ByteBuffer.allocate(2 * 8).order(ByteOrder.BIG_ENDIAN);
		source.putDouble(0, 11.5);
		source.putDouble(8, -6.25);
		ByteBuffer destination = ByteBuffer.allocate(2 * 8).order(ByteOrder.LITTLE_ENDIAN);

		new ByteBufferTransfer(source, Precision.FP64,
				destination, Precision.FP64).copyAll();

		Assert.assertEquals(11.5, destination.getDouble(0), 0.0);
		Assert.assertEquals(-6.25, destination.getDouble(8), 0.0);
	}

	/**
	 * Two transfers sharing a destination interleave their sources by
	 * alternating {@link ByteBufferTransfer#copyNext()}.
	 */
	@Test(timeout = 60000)
	public void interleavedSources() {
		ByteBuffer real = ByteBuffer.allocate(2 * 4).order(ByteOrder.nativeOrder());
		real.asFloatBuffer().put(new float[] { 1.0f, 2.0f });
		ByteBuffer imag = ByteBuffer.allocate(2 * 4).order(ByteOrder.nativeOrder());
		imag.asFloatBuffer().put(new float[] { 0.5f, 0.75f });
		ByteBuffer destination = ByteBuffer.allocate(4 * 8).order(ByteOrder.nativeOrder());

		ByteBufferTransfer realTransfer =
				new ByteBufferTransfer(real, Precision.FP32, destination, Precision.FP64);
		ByteBufferTransfer imagTransfer =
				new ByteBufferTransfer(imag, Precision.FP32, destination, Precision.FP64);

		for (int i = 0; i < 2; i++) {
			realTransfer.copyNext();
			imagTransfer.copyNext();
		}

		Assert.assertEquals(1.0, destination.getDouble(0), 0.0);
		Assert.assertEquals(0.5, destination.getDouble(8), 0.0);
		Assert.assertEquals(2.0, destination.getDouble(16), 0.0);
		Assert.assertEquals(0.75, destination.getDouble(24), 0.0);
	}

	/**
	 * FP16 is rejected at construction.
	 */
	@Test(timeout = 60000, expected = UnsupportedOperationException.class)
	public void fp16Rejected() {
		new ByteBufferTransfer(ByteBuffer.allocate(8), Precision.FP16,
				ByteBuffer.allocate(8), Precision.FP32);
	}
}
