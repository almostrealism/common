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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;

import java.util.function.Supplier;

/**
 * A computation for performing convolution using the Fast Fourier Transform (FFT).
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
 * <ol>
 *   <li>Zero-pad signal and kernel to length N+M-1 (next power of 2)</li>
 *   <li>Convert to complex format (real + 0i)</li>
 *   <li>Compute FFT of both</li>
 *   <li>Multiply element-wise (complex multiplication)</li>
 *   <li>Compute inverse FFT</li>
 *   <li>Extract real part as result</li>
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
public class FFTConvolution implements Supplier<Evaluable<PackedCollection>>, TemporalFeatures {

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

		TraversalPolicy signalShape = CollectionFeatures.getInstance().shape(signal);
		TraversalPolicy kernelShape = CollectionFeatures.getInstance().shape(kernel);

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

	@Override
	public Evaluable<PackedCollection> get() {
		return args -> {
			// Get input data
			PackedCollection signalData = (PackedCollection) ((Producer) signal).get().evaluate(args);
			PackedCollection kernelData = (PackedCollection) ((Producer) kernel).get().evaluate(args);

			// Zero-pad and convert to complex format
			PackedCollection signalComplex = new PackedCollection(shape(fftSize, 2));
			PackedCollection kernelComplex = new PackedCollection(shape(fftSize, 2));

			// Copy signal (real parts only)
			for (int i = 0; i < signalLength && i < signalData.getShape().getTotalSize(); i++) {
				signalComplex.setMem(i * 2, signalData.toDouble(i));     // Real
				signalComplex.setMem(i * 2 + 1, 0.0);                    // Imaginary
			}
			// Remaining positions are already zero-padded

			// Copy kernel (real parts only)
			for (int i = 0; i < kernelLength && i < kernelData.getShape().getTotalSize(); i++) {
				kernelComplex.setMem(i * 2, kernelData.toDouble(i));     // Real
				kernelComplex.setMem(i * 2 + 1, 0.0);                    // Imaginary
			}

			// Compute FFT of both
			FourierTransform fftSignal = fft(fftSize, cp(signalComplex), requirements);
			FourierTransform fftKernel = fft(fftSize, cp(kernelComplex), requirements);

			PackedCollection signalFreq = fftSignal.get().evaluate();
			PackedCollection kernelFreq = fftKernel.get().evaluate();

			// Complex multiplication: (a+bi)(c+di) = (ac-bd) + (ad+bc)i
			PackedCollection productFreq = new PackedCollection(shape(fftSize, 2));
			for (int i = 0; i < fftSize; i++) {
				double a = signalFreq.toDouble(i * 2);       // Real part of signal
				double b = signalFreq.toDouble(i * 2 + 1);   // Imag part of signal
				double c = kernelFreq.toDouble(i * 2);       // Real part of kernel
				double d = kernelFreq.toDouble(i * 2 + 1);   // Imag part of kernel

				double realPart = a * c - b * d;
				double imagPart = a * d + b * c;

				productFreq.setMem(i * 2, realPart);
				productFreq.setMem(i * 2 + 1, imagPart);
			}

			// Inverse FFT
			FourierTransform ifftResult = ifft(fftSize, cp(productFreq), requirements);
			PackedCollection convResult = ifftResult.get().evaluate();

			// Extract real part for output (truncate to actual convolution length)
			PackedCollection output = new PackedCollection(outputShape);
			for (int i = 0; i < outputLength; i++) {
				output.setMem(i, convResult.toDouble(i * 2));  // Real part only
			}

			return output;
		};
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
