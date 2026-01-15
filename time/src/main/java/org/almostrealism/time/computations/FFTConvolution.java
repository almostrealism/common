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

package org.almostrealism.time.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.time.TemporalFeatures;

/**
 * A GPU-accelerated computation for performing convolution using the Fast Fourier Transform (FFT).
 *
 * <p>FFT-based convolution is more efficient than direct time-domain convolution
 * for large signals or kernels. The time complexity is O(N log N) instead of O(N*M)
 * for direct convolution, where N is the signal length and M is the kernel length.</p>
 *
 * <h2>Algorithm</h2>
 * <p>The convolution theorem states that convolution in the time domain equals
 * multiplication in the frequency domain:</p>
 * <pre>
 * signal * kernel = IFFT(FFT(signal) . FFT(kernel))
 * </pre>
 *
 * <h2>Implementation Details</h2>
 * <p>All operations are performed on GPU/accelerator hardware:</p>
 * <ol>
 *   <li>Zero-pad signal and kernel to length N+M-1 (next power of 2)</li>
 *   <li>Convert to complex format using GPU operations</li>
 *   <li>Compute FFT of both (GPU-accelerated)</li>
 *   <li>Multiply element-wise using complex multiplication (GPU)</li>
 *   <li>Compute inverse FFT (GPU-accelerated)</li>
 *   <li>Extract real part as result (GPU)</li>
 * </ol>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li><strong>Convolution Reverb:</strong> Apply room impulse responses to audio</li>
 *   <li><strong>Long FIR Filtering:</strong> Apply filters with many coefficients efficiently</li>
 *   <li><strong>Cross-correlation:</strong> Find patterns in signals</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Convolve audio with impulse response
 * Producer<PackedCollection> audio = ...;    // Audio signal
 * Producer<PackedCollection> ir = ...;       // Impulse response
 *
 * FFTConvolution conv = new FFTConvolution(audio, ir);
 * PackedCollection result = conv.get().evaluate();
 * // Result length = audio.length + ir.length - 1
 * }</pre>
 *
 * @see FourierTransform
 * @see org.almostrealism.time.TemporalFeatures#fftConvolve(Producer, Producer)
 *
 * @author Michael Murray
 */
public class FFTConvolution implements TemporalFeatures {

	private final Producer<PackedCollection> signal;
	private final Producer<PackedCollection> kernel;
	private final int signalLength;
	private final int kernelLength;
	private final int fftSize;
	private final int outputLength;
	private final TraversalPolicy outputShape;
	private ComputeRequirement[] requirements;

	/**
	 * Constructs an FFT convolution computation.
	 *
	 * @param signal The input signal producer
	 * @param kernel The convolution kernel (filter/impulse response) producer
	 */
	public FFTConvolution(Producer<PackedCollection> signal, Producer<PackedCollection> kernel) {
		this.signal = signal;
		this.kernel = kernel;

		TraversalPolicy signalShape = shape(signal);
		TraversalPolicy kernelShape = shape(kernel);

		this.signalLength = signalShape.getSize();
		this.kernelLength = kernelShape.getSize();
		this.outputLength = signalLength + kernelLength - 1;
		this.fftSize = nextPowerOfTwo(outputLength);
		this.outputShape = shape(outputLength);
		this.requirements = new ComputeRequirement[0];
	}

	/**
	 * Sets compute requirements for this convolution.
	 *
	 * @param requirements The compute requirements
	 * @return this FFTConvolution for method chaining
	 */
	public FFTConvolution setComputeRequirements(ComputeRequirement... requirements) {
		this.requirements = requirements;
		return this;
	}

	/**
	 * Returns the FFT size used for computation.
	 *
	 * @return the FFT size (power of 2)
	 */
	public int getFftSize() {
		return fftSize;
	}

	/**
	 * Returns the output length.
	 *
	 * @return the output length (signalLength + kernelLength - 1)
	 */
	public int getOutputLength() {
		return outputLength;
	}

	/**
	 * Returns the output shape.
	 *
	 * @return the output shape
	 */
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	/**
	 * Returns a GPU-accelerated producer that performs the FFT convolution.
	 *
	 * <p>The entire computation pipeline runs on the accelerator:</p>
	 * <ol>
	 *   <li>Zero-pad inputs to FFT size</li>
	 *   <li>Convert real signals to complex format</li>
	 *   <li>Forward FFT both signals</li>
	 *   <li>Complex multiplication in frequency domain</li>
	 *   <li>Inverse FFT the product</li>
	 *   <li>Extract real part and trim to output length</li>
	 * </ol>
	 *
	 * @return CollectionProducer that computes the convolution on GPU
	 */
	public CollectionProducer get() {
		// Step 1: Zero-pad signal to fftSize
		// Pad from [signalLength] to [fftSize]
		CollectionProducer paddedSignal = pad(
				shape(fftSize),
				signal,
				0  // Position at start
		);

		// Step 2: Convert signal to complex format [fftSize, 2]
		// Real parts from signal, imaginary parts are zeros
		CollectionProducer signalComplex = complexFromParts(
				paddedSignal,
				zeros(shape(fftSize))
		);

		// Step 3: Zero-pad kernel to fftSize
		CollectionProducer paddedKernel = pad(
				shape(fftSize),
				kernel,
				0  // Position at start
		);

		// Step 4: Convert kernel to complex format [fftSize, 2]
		CollectionProducer kernelComplex = complexFromParts(
				paddedKernel,
				zeros(shape(fftSize))
		);

		// Step 5: FFT both (GPU-accelerated)
		FourierTransform signalFFT = fft(fftSize, signalComplex, requirements);
		FourierTransform kernelFFT = fft(fftSize, kernelComplex, requirements);

		// Step 6: Complex multiplication in frequency domain (GPU-accelerated)
		// multiplyComplex handles (a+bi)(c+di) = (ac-bd) + (ad+bc)i
		CollectionProducer product = multiplyComplex(
				signalFFT,
				kernelFFT
		);

		// Step 7: Inverse FFT (GPU-accelerated)
		FourierTransform ifftResult = ifft(fftSize, product, requirements);

		// Step 8: Extract real parts and trim to output length
		// The IFFT output has shape [1, 2, fftSize] with traverse(1)
		// Data layout is interleaved: [re0, im0, re1, im1, ...]
		// We need to extract real parts at even indices (0, 2, 4, ...) and take only outputLength values
		CollectionProducer flatIfft = ifftResult.traverseEach();

		// Create a computation that extracts real parts with stride 2
		// For output index idx, read from input at idx * 2
		return new DefaultTraversableExpressionComputation(
				"extractRealParts",
				outputShape,
				args -> CollectionExpression.create(outputShape,
						idx -> args[1].getValueAt(idx.multiply(2))),
				flatIfft
		);
	}

	/**
	 * Finds the next power of 2 greater than or equal to n.
	 *
	 * @param n input value
	 * @return smallest power of 2 >= n
	 */
	private static int nextPowerOfTwo(int n) {
		int power = 1;
		while (power < n) {
			power *= 2;
		}
		return power;
	}
}
