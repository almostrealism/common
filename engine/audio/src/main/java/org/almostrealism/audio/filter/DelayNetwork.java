/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.audio.filter;

import io.almostrealism.compute.Process;
import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalFactor;

import java.util.function.Supplier;

/**
 * A feedback delay network for creating reverb and diffusion effects.
 *
 * <p>DelayNetwork implements a multi-tap delay network with feedback matrix mixing.
 * It uses a Householder matrix for the feedback coefficients to ensure stability
 * and even energy distribution. This is useful for creating diffuse reverb tails
 * and spatial effects.</p>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Multiple parallel delay lines with different lengths</li>
 *   <li>Householder feedback matrix for mixing between lines</li>
 *   <li>Randomized delay times for natural-sounding diffusion</li>
 *   <li>Hardware-accelerated matrix multiplication</li>
 * </ul>
 *
 * @see TemporalFactor
 * @see BasicDelayCell
 */
public class DelayNetwork implements TemporalFactor<PackedCollection>, Lifecycle, CodeFeatures {
	/** When true, wraps tick operations in an isolated process for debugging. */
	public static boolean enableIsolation = false;

	/** Overall output gain applied to the summed delay network output. */
	private final double gain;

	/** Number of parallel delay lines in the network. */
	private final int size;

	/** Maximum number of frames any single delay line can hold. */
	private final int maxDelayFrames;

	/** When true, uses parallel (each-traversal) operations; when false, uses sequential. */
	private final boolean parallel;

	/** The Householder feedback matrix used to mix delay line outputs. */
	private final double[][] matrix;

	/** The incoming audio producer whose output is fed into the network. */
	private Producer<PackedCollection> input;

	/** Temporary storage for the values read from each delay line at the current tick. */
	private final PackedCollection delayIn;

	/** Temporary storage for the values written to each delay line at the current tick. */
	private final PackedCollection delayOut;

	/** Circular delay buffers; shape is (size, maxDelayFrames). */
	private final PackedCollection delayBuffer;

	/** Length of each delay line's circular buffer in frames. */
	private final PackedCollection bufferLengths;

	/** Current write index into each delay line's circular buffer. */
	private final PackedCollection bufferIndices;

	/** Flattened feedback matrix of shape (size, size) used for matrix-vector multiplication. */
	private final PackedCollection feedback;

	/** Single-frame output holding the summed delay network result. */
	private final PackedCollection output;

	/**
	 * Creates a DelayNetwork with 128 delay lines and a 1.5-second maximum delay.
	 *
	 * @param sampleRate audio sample rate in Hz
	 * @param parallel   true to use parallel each-traversal operations
	 */
	public DelayNetwork(int sampleRate, boolean parallel) {
		this(128, sampleRate, parallel);
	}

	/**
	 * Creates a DelayNetwork with the specified number of delay lines and a 1.5-second maximum delay.
	 *
	 * @param size       number of parallel delay lines
	 * @param sampleRate audio sample rate in Hz
	 * @param parallel   true to use parallel each-traversal operations
	 */
	public DelayNetwork(int size, int sampleRate, boolean parallel) {
		this(0.1, size, 1.5, sampleRate, parallel);
	}

	/**
	 * Creates a DelayNetwork with full control over all parameters.
	 *
	 * @param gain       overall output gain scaling factor
	 * @param size       number of parallel delay lines
	 * @param duration   maximum delay time in seconds
	 * @param sampleRate audio sample rate in Hz
	 * @param parallel   true to use parallel each-traversal operations
	 */
	public DelayNetwork(double gain, int size, double duration, int sampleRate, boolean parallel) {
		this.gain = gain;
		this.size = size;
		this.maxDelayFrames = (int) (duration * sampleRate);
		this.parallel = parallel;

		this.matrix = randomHouseholderMatrix(size, 1.0);
		// this.matrix = householderMatrix(_normalize(c(1).repeat(size)), 1.3 / size);

		this.delayIn = new PackedCollection(size);
		this.delayOut = new PackedCollection(size);
		this.delayBuffer = new PackedCollection(shape(size, maxDelayFrames));
		this.bufferLengths = new PackedCollection(size);
		this.bufferIndices = new PackedCollection(size);
		this.feedback = new PackedCollection(shape(size, size));
		this.output = new PackedCollection(1);

		this.feedback.fill(pos -> matrix[pos[0]][pos[1]]);
		this.bufferLengths.fill(pos -> Math.max(1, Math.floor(0.1 * maxDelayFrames + 0.9 * Math.random() * maxDelayFrames)));
		this.bufferIndices.fill(pos -> 0.0);
	}

	@Override
	public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
		this.input = value;
		return p(output);
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("DelayNetwork Tick");

		/*
		 * D = value from the buffer
		 * New value in the buffer = D * F + Input
		 * Output = D
		 */
		if (parallel) {
			// D = value from the buffer
			tick.add(a(cp(delayIn).each(),
					c(p(delayBuffer), shape(delayBuffer), integers(0, size), traverseEach(p(bufferIndices)))));
			// O = D * F + Input (TODO Should be parallel)
			tick.add(a(p(delayOut), matmul(p(feedback), p(delayIn)).add(c(input, 0).mul(gain).repeat(size))));
			// New value in the buffer = O
			tick.add(a(c(p(delayBuffer), shape(delayBuffer), integers(0, size), traverseEach(p(bufferIndices))),
					traverseEach(p(delayOut))));
			// Step forward (TODO Should be parallel)
			tick.add(a(p(bufferIndices), add(p(bufferIndices), c(1).repeat(size)).mod(p(bufferLengths))));
			// Output = D
			tick.add(a(p(output), sum(p(delayIn))));
		} else {
			// D = value from the buffer
			tick.add(a(
					cp(delayIn),
					traverse(0, c(p(delayBuffer), shape(delayBuffer), integers(0, size), traverseEach(p(bufferIndices))))));
			// O = D * F + Input
			tick.add(a(
					p(delayOut),
					matmul(p(feedback), p(delayIn)).add(c(input, 0).mul(gain).repeat(size))));
			// New value in the buffer = O
			tick.add(a(
					traverse(0, c(p(delayBuffer), shape(delayBuffer), integers(0, size), traverseEach(p(bufferIndices)))),
					p(delayOut)));
			// Step forward
			tick.add(a(
					p(bufferIndices),
					add(p(bufferIndices), c(1).repeat(size)).mod(p(bufferLengths))));
			// Output = D
			tick.add(a(p(output), sum(p(delayIn))));
		}

		OperationList op = new OperationList("DelayNetwork");

		if (enableIsolation) {
//			for (Supplier<Runnable> p : tick) {
//				op.add(Process.isolated(p));
//			}

			op.add(Process.isolated(tick));
		}
//
//		op.add(() -> () -> {
//			String info = "tick: " +
//					input.getClass() + " " +
//					delayIn.getCount() + " " +
//					delayOut.getCount() + " " +
//					bufferIndices.getCount() + " " +
//					bufferLengths.getCount() + " " +
//					delayBuffer.getCount() + " " +
//					output.getCount();
//			if (delayIn.doubleStream().filter(d -> d != 0.0).count() > 5) {
//				System.out.println(info);
//			}
//		});

		return enableIsolation ? op : tick;
	}

	@Override
	public void reset() {
		this.delayIn.fill(0.0);
		this.delayOut.fill(0.0);
		this.delayBuffer.fill(0.0);
		this.bufferIndices.fill(0.0);
	}

	/**
	 * Generates a Householder reflection matrix from a random unit vector, scaled by {@code scale/size}.
	 *
	 * @param size  matrix dimension and length of the random vector
	 * @param scale overall scale factor applied to each element (divided by size)
	 * @return a size-by-size Householder feedback matrix
	 */
	public double[][] randomHouseholderMatrix(int size, double scale) {
		return householderMatrix(rand(size), scale / size);
	}

	/**
	 * Generates a Householder reflection matrix from the given unit-vector producer.
	 *
	 * @param v     producer of the reflection vector (will be normalized before use)
	 * @param scale overall scale factor applied to each matrix element
	 * @return the resulting Householder matrix as a 2D double array
	 */
	public double[][] householderMatrix(Producer<PackedCollection> v, double scale) {
		return householderMatrix((normalize(v).evaluate()).toArray(), scale);
	}

	/**
	 * Constructs a scaled Householder reflection matrix H = scale * (I - 2*v*v^T).
	 *
	 * @param v     the unit-length reflection vector
	 * @param scale overall scale factor applied to each element
	 * @return the resulting Householder matrix as a 2D double array
	 */
	public double[][] householderMatrix(double[] v, double scale) {
		int size = v.length;
		double[][] householder = new double[size][size];

		// Compute outer product of v
		double[][] outerProduct = new double[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				outerProduct[i][j] = 2 * v[i] * v[j];
			}
		}

		double id = 1.0 / size;

		// Subtract the outer product from identity matrix
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				householder[i][j] = scale * ((i == j ? 1.0 : 0.0) - outerProduct[i][j]);
//				householder[i][j] = ((i == j ? id : 0.0) - outerProduct[i][j]);
			}
		}

		return householder;
	}

	/**
	 * Returns the transpose of the given matrix.
	 *
	 * @param matrix the input matrix (rows x columns)
	 * @return a new matrix (columns x rows) that is the transpose of the input
	 */
	public static double[][] transpose(double[][] matrix) {
		int rows = matrix.length;
		int columns = matrix[0].length;
		double[][] transpose = new double[columns][rows];

		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < columns; j++) {
				transpose[j][i] = matrix[i][j];
			}
		}

		return transpose;
	}

	/**
	 * Multiplies two matrices and returns the result.
	 *
	 * @param firstMatrix  the left operand matrix (r1 x c1)
	 * @param secondMatrix the right operand matrix (c1 x c2)
	 * @return the product matrix (r1 x c2)
	 */
	public static double[][] multiplyMatrices(double[][] firstMatrix, double[][] secondMatrix) {
		int r1 = firstMatrix.length;
		int c1 = firstMatrix[0].length;
		int c2 = secondMatrix[0].length;
		double[][] product = new double[r1][c2];

		for (int i = 0; i < r1; i++) {
			for (int j = 0; j < c2; j++) {
				for (int k = 0; k < c1; k++) {
					product[i][j] += firstMatrix[i][k] * secondMatrix[k][j];
				}
			}
		}

		return product;
	}
}