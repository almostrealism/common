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

import java.util.List;
import java.util.function.Supplier;

/**
 * A computation for performing Short-Time Fourier Transform (STFT) on a time-domain signal.
 *
 * <p>The STFT divides a signal into overlapping frames, applies a window function to each frame,
 * and computes the FFT of each windowed frame. The result is a time-frequency representation
 * (spectrogram) that shows how the frequency content of a signal changes over time.</p>
 *
 * <h2>What is STFT?</h2>
 * <p>The Short-Time Fourier Transform is the foundation of spectral analysis for non-stationary
 * signals. Unlike the standard FFT which gives a single frequency representation of the entire
 * signal, STFT provides a sequence of frequency snapshots at different time positions.</p>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><strong>FFT Size:</strong> The number of samples in each analysis frame (must be power of 2)</li>
 *   <li><strong>Hop Size:</strong> The number of samples to advance between consecutive frames.
 *       Common choices are fftSize/4 (75% overlap) or fftSize/2 (50% overlap)</li>
 *   <li><strong>Window Type:</strong> The window function applied to each frame to reduce spectral leakage</li>
 * </ul>
 *
 * <h2>Output Format</h2>
 * <p>The output is a 3D array with shape [numFrames, fftSize, 2] where:</p>
 * <ul>
 *   <li>numFrames = (signalLength - fftSize) / hopSize + 1</li>
 *   <li>fftSize is the number of frequency bins</li>
 *   <li>2 represents [real, imaginary] pairs for complex values</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create STFT with 1024-sample frames, 256-sample hop, Hann window
 * Producer<PackedCollection> signal = ...; // Time-domain audio signal
 *
 * STFTComputation stft = new STFTComputation(
 *     1024,                        // FFT size
 *     256,                         // Hop size (75% overlap)
 *     WindowComputation.Type.HANN, // Window type
 *     signal
 * );
 *
 * PackedCollection spectrogram = stft.get().evaluate();
 *
 * // Access the magnitude spectrum of frame 10
 * PackedCollection frame10 = spectrogram.range(shape(1024, 2), 10 * 1024 * 2);
 * }</pre>
 *
 * <h2>Frequency Resolution vs Time Resolution</h2>
 * <p>There is a fundamental trade-off in STFT:</p>
 * <ul>
 *   <li>Larger FFT size = better frequency resolution, worse time resolution</li>
 *   <li>Smaller FFT size = worse frequency resolution, better time resolution</li>
 * </ul>
 * <p>Choose FFT size based on your application needs.</p>
 *
 * @see FourierTransform
 * @see WindowComputation
 * @see org.almostrealism.time.TemporalFeatures#stft(int, int, WindowComputation.Type, Producer)
 *
 * @author Michael Murray
 */
public class STFTComputation implements TemporalFeatures, Supplier<Evaluable<PackedCollection>> {

	private final int fftSize;
	private final int hopSize;
	private final WindowComputation.Type windowType;
	private final Producer<PackedCollection> signal;
	private final int numFrames;
	private final TraversalPolicy outputShape;
	private ComputeRequirement[] requirements;

	/**
	 * Constructs an STFT computation.
	 *
	 * @param fftSize    The FFT frame size (must be power of 2)
	 * @param hopSize    The hop size (number of samples between frames)
	 * @param windowType The window function type to apply
	 * @param signal     The input time-domain signal
	 */
	public STFTComputation(int fftSize, int hopSize, WindowComputation.Type windowType,
						   Producer<PackedCollection> signal) {
		this(fftSize, hopSize, windowType, signal,
				computeNumFrames(computeSignalLength(signal), fftSize, hopSize));
	}

	private static int computeSignalLength(Producer<PackedCollection> signal) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(signal);
		return shape.getSize();
	}

	/**
	 * Constructs an STFT computation with explicit number of frames.
	 *
	 * @param fftSize    The FFT frame size (must be power of 2)
	 * @param hopSize    The hop size (number of samples between frames)
	 * @param windowType The window function type to apply
	 * @param signal     The input time-domain signal
	 * @param numFrames  The number of frames to compute
	 */
	public STFTComputation(int fftSize, int hopSize, WindowComputation.Type windowType,
						   Producer<PackedCollection> signal, int numFrames) {
		if (!isPowerOfTwo(fftSize)) {
			throw new IllegalArgumentException("FFT size must be power of 2: " + fftSize);
		}
		if (hopSize <= 0) {
			throw new IllegalArgumentException("Hop size must be positive: " + hopSize);
		}

		this.fftSize = fftSize;
		this.hopSize = hopSize;
		this.windowType = windowType;
		this.signal = signal;
		this.numFrames = numFrames;
		this.outputShape = shape(numFrames, fftSize, 2);
		this.requirements = new ComputeRequirement[0];
	}

	/**
	 * Sets compute requirements for this STFT.
	 *
	 * @param requirements The compute requirements
	 * @return this STFTComputation for method chaining
	 */
	public STFTComputation setComputeRequirements(ComputeRequirement... requirements) {
		this.requirements = requirements;
		return this;
	}

	/**
	 * Returns the FFT frame size.
	 *
	 * @return the FFT size
	 */
	public int getFftSize() {
		return fftSize;
	}

	/**
	 * Returns the hop size.
	 *
	 * @return the hop size
	 */
	public int getHopSize() {
		return hopSize;
	}

	/**
	 * Returns the window type.
	 *
	 * @return the window type
	 */
	public WindowComputation.Type getWindowType() {
		return windowType;
	}

	/**
	 * Returns the number of frames in the output.
	 *
	 * @return the number of frames
	 */
	public int getNumFrames() {
		return numFrames;
	}

	/**
	 * Returns the output shape.
	 *
	 * @return the output shape [numFrames, fftSize, 2]
	 */
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	@Override
	public Evaluable<PackedCollection> get() {
		// Pre-compute the window coefficients
		PackedCollection windowCoeffs = window(windowType, fftSize).get().evaluate();

		// Create the evaluable that will process all frames
		return args -> {
			PackedCollection inputSignal = (PackedCollection) ((Producer) signal).get().evaluate(args);
			PackedCollection output = new PackedCollection(outputShape);

			// Process each frame
			for (int frame = 0; frame < numFrames; frame++) {
				int startSample = frame * hopSize;

				// Extract frame and apply window
				PackedCollection frameData = new PackedCollection(shape(fftSize, 2));
				for (int i = 0; i < fftSize; i++) {
					double sampleValue = (startSample + i < inputSignal.getShape().getTotalSize())
							? inputSignal.toDouble(startSample + i)
							: 0.0;
					double windowedValue = sampleValue * windowCoeffs.toDouble(i);

					// Store as complex with zero imaginary part
					frameData.setMem(i * 2, windowedValue);     // Real
					frameData.setMem(i * 2 + 1, 0.0);           // Imaginary
				}

				// Compute FFT
				FourierTransform fft = fft(fftSize, cp(frameData), requirements);
				PackedCollection fftResult = fft.get().evaluate();

				// Copy FFT result to output
				int outputOffset = frame * fftSize * 2;
				for (int i = 0; i < fftSize * 2; i++) {
					output.setMem(outputOffset + i, fftResult.toDouble(i));
				}
			}

			return output;
		};
	}

	/**
	 * Computes the number of frames for a given signal length.
	 *
	 * @param signalLength The length of the input signal
	 * @param fftSize      The FFT frame size
	 * @param hopSize      The hop size
	 * @return The number of complete frames that can be extracted
	 */
	public static int computeNumFrames(int signalLength, int fftSize, int hopSize) {
		if (signalLength < fftSize) {
			return 0;
		}
		return (signalLength - fftSize) / hopSize + 1;
	}

	private static boolean isPowerOfTwo(int n) {
		return n > 0 && (n & (n - 1)) == 0;
	}
}
