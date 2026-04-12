/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Heap;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * A 192-bit color data storage implementation for {@link RGB}, storing three double-precision
 * (64-bit) floating-point channel values (red, green, blue) in a {@link PackedCollection}.
 *
 * <p>This class implements the {@link RGB.Data} interface, providing channel-level
 * add, scale, sum, read, and write operations over memory managed by the Almost Realism
 * hardware layer. The 192-bit depth refers to three 64-bit doubles (3 × 64 = 192 bits).</p>
 *
 * <p>Instances may either own their memory (default constructor) or view a sub-region
 * of an existing {@link org.almostrealism.hardware.MemoryData} buffer (delegate constructor).</p>
 *
 * @see RGB
 * @see RGB.Data
 * @author Michael Murray
 */
public class RGBData192 extends PackedCollection implements RGB.Data {
	/** The color depth in bits (3 channels × 64 bits = 192). */
	public static final int depth = 192;

	/**
	 * Constructs a new {@link RGBData192} with its own 3-element memory block.
	 */
	public RGBData192() {
		super(new TraversalPolicy(3), 0);
	}

	/**
	 * Constructs a {@link RGBData192} that views an existing memory buffer.
	 *
	 * @param delegate       the backing memory buffer
	 * @param delegateOffset the offset within the delegate where this instance's 3 doubles start
	 */
	protected RGBData192(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(3), 0, delegate, delegateOffset);
	}

	/**
	 * Adds {@code r} to the channel at index {@code i} (0=red, 1=green, 2=blue).
	 *
	 * @param i the channel index (0, 1, or 2)
	 * @param r the value to add
	 */
	@Override
	public void add(int i, double r) {
		double rgb[] = toArray();
		rgb[i] += r;
		setMem(rgb);
	}

	/**
	 * Multiplies the channel at index {@code i} by {@code r}.
	 *
	 * @param i the channel index (0=red, 1=green, 2=blue)
	 * @param r the scaling factor
	 */
	@Override
	public void scale(int i, double r) {
		double rgb[] = toArray();
		rgb[i] *= r;
		setMem(rgb);
	}

	/** Returns the sum of the components (not vector length). */
	@Override
	public double sum() {
		double rgb[] = toArray();
		return rgb[0] + rgb[1] + rgb[2];
	}

	/**
	 * Reads the three channel values from an {@link ObjectInput} stream.
	 *
	 * @param in the input stream from which to read the red, green, and blue values
	 * @throws IOException if an I/O error occurs during reading
	 */
	@Override
	public void read(ObjectInput in) throws IOException {
		double rgb[] = new double[3];
		rgb[0] = in.readDouble();
		rgb[1] = in.readDouble();
		rgb[2] = in.readDouble();
		setMem(rgb);
	}

	/**
	 * Writes the three channel values to an {@link ObjectOutput} stream.
	 *
	 * @param out the output stream to which red, green, and blue values are written
	 * @throws IOException if an I/O error occurs during writing
	 */
	@Override
	public void write(ObjectOutput out) throws IOException {
		double rgb[] = toArray();
		out.writeDouble(rgb[0]);
		out.writeDouble(rgb[1]);
		out.writeDouble(rgb[2]);
	}

	/**
	 * Returns the default heap used as the backing memory allocator.
	 *
	 * @return the default {@link Heap} instance
	 */
	@Override
	public Heap getDefaultDelegate() { return Heap.getDefault(); }

	/**
	 * Reads all three channel values from backing memory into a new array.
	 *
	 * @return a double array of length 3 containing [red, green, blue]
	 */
	@Override
	public double[] toArray() {
		double d[] = new double[3];
		getMem(0, d, 0, 3);
		return d;
	}
}
