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
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.List;

/**
 * A hardware-accelerated computation for generating window function coefficients
 * used in spectral analysis and signal processing.
 *
 * <p>{@link WindowComputation} generates window coefficients for various standard
 * window functions (Hann, Hamming, Blackman, etc.) that can execute on GPU/accelerator
 * hardware. These windows are essential for Short-Time Fourier Transform (STFT),
 * spectral analysis, and reducing spectral leakage in frequency-domain operations.</p>
 *
 * <h2>What are Window Functions?</h2>
 * <p>Window functions are mathematical functions that taper a signal to zero at its
 * boundaries. When applied before an FFT, they reduce spectral leakage caused by
 * the assumption of periodic signals. Different window types offer trade-offs between:</p>
 * <ul>
 *   <li><strong>Main lobe width:</strong> Frequency resolution</li>
 *   <li><strong>Side lobe level:</strong> Spectral leakage suppression</li>
 *   <li><strong>Side lobe roll-off:</strong> How quickly side lobes decay</li>
 * </ul>
 *
 * <h2>Supported Window Types</h2>
 *
 * <h3>Hann (Hanning) Window</h3>
 * <pre>
 * w[n] = 0.5 * (1 - cos(2*PI * n / (N-1)))
 * </pre>
 * <p>Good general-purpose window with moderate frequency resolution and low spectral leakage.</p>
 *
 * <h3>Hamming Window</h3>
 * <pre>
 * w[n] = 0.54 - 0.46 * cos(2*PI * n / (N-1))
 * </pre>
 * <p>Similar to Hann but with slightly better side lobe suppression at the cost of
 * not reaching zero at boundaries.</p>
 *
 * <h3>Blackman Window</h3>
 * <pre>
 * w[n] = 0.42 - 0.5 * cos(2*PI * n / (N-1)) + 0.08 * cos(4*PI * n / (N-1))
 * </pre>
 * <p>Excellent side lobe suppression (-58 dB) but wider main lobe than Hann/Hamming.</p>
 *
 * <h3>Bartlett (Triangular) Window</h3>
 * <pre>
 * w[n] = 1 - |2n/(N-1) - 1|
 * </pre>
 * <p>Simple linear taper. Lower side lobe suppression but computationally simple.</p>
 *
 * <h3>Flat-Top Window</h3>
 * <pre>
 * w[n] = 0.21557895 - 0.41663158 * cos(2*PI*n/(N-1)) + 0.277263158 * cos(4*PI*n/(N-1))
 *        - 0.083578947 * cos(6*PI*n/(N-1)) + 0.006947368 * cos(8*PI*n/(N-1))
 * </pre>
 * <p>Optimized for amplitude accuracy in spectral analysis. Very wide main lobe but
 * minimal amplitude error for sinusoidal components.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Generate a Hann Window</h3>
 * <pre>{@code
 * // Create 1024-point Hann window
 * WindowComputation window = new WindowComputation(WindowComputation.Type.HANN, 1024);
 * PackedCollection coefficients = window.get().evaluate();
 *
 * // Apply to signal
 * for (int i = 0; i < signal.length; i++) {
 *     signal[i] *= coefficients.toDouble(i);
 * }
 * }</pre>
 *
 * <h3>Using Factory Methods</h3>
 * <pre>{@code
 * // Via TemporalFeatures
 * class MyProcessor implements TemporalFeatures {
 *     void process() {
 *         CollectionProducer window = hannWindow(2048);
 *         CollectionProducer windowed = signal.multiply(window);
 *     }
 * }
 * }</pre>
 *
 * <h3>STFT Preparation</h3>
 * <pre>{@code
 * // Prepare windowed frames for STFT
 * int fftSize = 2048;
 * int hopSize = 512;
 * WindowComputation window = new WindowComputation(Type.HANN, fftSize);
 * PackedCollection windowCoeffs = window.get().evaluate();
 *
 * // For each frame
 * for (int frame = 0; frame < numFrames; frame++) {
 *     int start = frame * hopSize;
 *     // Extract frame and apply window
 *     for (int i = 0; i < fftSize; i++) {
 *         windowedFrame[i] = signal[start + i] * windowCoeffs.toDouble(i);
 *     }
 *     // Apply FFT to windowed frame...
 * }
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Time Complexity:</strong> O(N) where N is window size</li>
 *   <li><strong>Space Complexity:</strong> O(N) for output coefficients</li>
 *   <li><strong>Hardware Acceleration:</strong> GPU/OpenCL/Metal compatible</li>
 *   <li><strong>Parallelism:</strong> Embarrassingly parallel (each coefficient independent)</li>
 * </ul>
 *
 * <h2>Choosing a Window Type</h2>
 * <table border="1">
 * <caption>Window Type Comparison</caption>
 * <tr><th>Window</th><th>Main Lobe</th><th>Side Lobe</th><th>Best For</th></tr>
 * <tr><td>Hann</td><td>Moderate</td><td>-31 dB</td><td>General spectral analysis</td></tr>
 * <tr><td>Hamming</td><td>Moderate</td><td>-43 dB</td><td>Speech processing</td></tr>
 * <tr><td>Blackman</td><td>Wide</td><td>-58 dB</td><td>High dynamic range signals</td></tr>
 * <tr><td>Bartlett</td><td>Narrow</td><td>-27 dB</td><td>Simple applications</td></tr>
 * <tr><td>Flat-Top</td><td>Very Wide</td><td>-93 dB</td><td>Amplitude measurement</td></tr>
 * </table>
 *
 * @see FourierTransform
 * @see org.almostrealism.time.TemporalFeatures#hannWindow(int)
 * @see org.almostrealism.time.TemporalFeatures#hammingWindow(int)
 * @see org.almostrealism.time.TemporalFeatures#blackmanWindow(int)
 *
 * @author Michael Murray
 */
public class WindowComputation extends CollectionProducerComputationBase {

	/**
	 * Enumeration of supported window function types.
	 */
	public enum Type {
		/**
		 * Hann (Hanning) window: w[n] = 0.5 * (1 - cos(2*PI * n / (N-1)))
		 */
		HANN,

		/**
		 * Hamming window: w[n] = 0.54 - 0.46 * cos(2*PI * n / (N-1))
		 */
		HAMMING,

		/**
		 * Blackman window: w[n] = 0.42 - 0.5 * cos(2*PI*n/(N-1)) + 0.08 * cos(4*PI*n/(N-1))
		 */
		BLACKMAN,

		/**
		 * Bartlett (triangular) window: w[n] = 1 - |2n/(N-1) - 1|
		 */
		BARTLETT,

		/**
		 * Flat-top window for amplitude-accurate spectral analysis.
		 */
		FLATTOP
	}

	private final Type windowType;
	private final int windowSize;

	/**
	 * Constructs a window computation for generating window coefficients.
	 *
	 * @param type The type of window function to generate
	 * @param size The number of window coefficients (window size)
	 */
	public WindowComputation(Type type, int size) {
		super("window" + type.name(), new TraversalPolicy(size).traverse());
		this.windowType = type;
		this.windowSize = size;
	}

	/**
	 * Returns the window function type.
	 *
	 * @return the window type
	 */
	public Type getWindowType() {
		return windowType;
	}

	/**
	 * Returns the window size (number of coefficients).
	 *
	 * @return the window size
	 */
	public int getWindowSize() {
		return windowSize;
	}

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		Scope<PackedCollection> scope = super.getScope(context);

		Expression<?> n = kernel(context);
		// Use explicit double to ensure floating-point division
		Expression<?> nMinus1 = e((double) (windowSize - 1));

		// 2 * PI * n / (N-1)
		Expression<?> angle = e(2.0).multiply(pi()).multiply(n).divide(nMinus1);

		Expression<?> windowValue;

		switch (windowType) {
			case HANN:
				// w[n] = 0.5 * (1 - cos(2*PI * n / (N-1)))
				windowValue = e(0.5).multiply(e(1.0).subtract(angle.cos()));
				break;

			case HAMMING:
				// w[n] = 0.54 - 0.46 * cos(2*PI * n / (N-1))
				windowValue = e(0.54).subtract(e(0.46).multiply(angle.cos()));
				break;

			case BLACKMAN:
				// w[n] = 0.42 - 0.5 * cos(2*PI*n/(N-1)) + 0.08 * cos(4*PI*n/(N-1))
				Expression<?> angle2 = e(4.0).multiply(pi()).multiply(n).divide(nMinus1);
				windowValue = e(0.42)
						.subtract(e(0.5).multiply(angle.cos()))
						.add(e(0.08).multiply(angle2.cos()));
				break;

			case BARTLETT:
				// w[n] = 1 - |2n/(N-1) - 1|
				Expression<Double> normalized = (Expression<Double>) e(2.0).multiply(n).divide(nMinus1).subtract(e(1.0));
				windowValue = e(1.0).subtract(new Absolute(normalized));
				break;

			case FLATTOP:
				// Flat-top window with 5 terms for accurate amplitude measurement
				// w[n] = a0 - a1*cos(2*PI*n/(N-1)) + a2*cos(4*PI*n/(N-1))
				//        - a3*cos(6*PI*n/(N-1)) + a4*cos(8*PI*n/(N-1))
				double a0 = 0.21557895;
				double a1 = 0.41663158;
				double a2 = 0.277263158;
				double a3 = 0.083578947;
				double a4 = 0.006947368;

				Expression<?> angle4 = e(4.0).multiply(pi()).multiply(n).divide(nMinus1);
				Expression<?> angle6 = e(6.0).multiply(pi()).multiply(n).divide(nMinus1);
				Expression<?> angle8 = e(8.0).multiply(pi()).multiply(n).divide(nMinus1);

				windowValue = e(a0)
						.subtract(e(a1).multiply(angle.cos()))
						.add(e(a2).multiply(angle4.cos()))
						.subtract(e(a3).multiply(angle6.cos()))
						.add(e(a4).multiply(angle8.cos()));
				break;

			default:
				throw new IllegalArgumentException("Unknown window type: " + windowType);
		}

		// Assign the computed window value to the output
		scope.getStatements().add(
				getCollectionArgumentVariable(0).getValueAt(n).assign(windowValue)
		);

		return scope;
	}

	@Override
	public WindowComputation generate(List<Process<?, ?>> children) {
		return new WindowComputation(windowType, windowSize);
	}

	/**
	 * Factory method to create a Hann window.
	 *
	 * @param size the window size
	 * @return a new WindowComputation for Hann window
	 */
	public static WindowComputation hann(int size) {
		return new WindowComputation(Type.HANN, size);
	}

	/**
	 * Factory method to create a Hamming window.
	 *
	 * @param size the window size
	 * @return a new WindowComputation for Hamming window
	 */
	public static WindowComputation hamming(int size) {
		return new WindowComputation(Type.HAMMING, size);
	}

	/**
	 * Factory method to create a Blackman window.
	 *
	 * @param size the window size
	 * @return a new WindowComputation for Blackman window
	 */
	public static WindowComputation blackman(int size) {
		return new WindowComputation(Type.BLACKMAN, size);
	}

	/**
	 * Factory method to create a Bartlett window.
	 *
	 * @param size the window size
	 * @return a new WindowComputation for Bartlett window
	 */
	public static WindowComputation bartlett(int size) {
		return new WindowComputation(Type.BARTLETT, size);
	}

	/**
	 * Factory method to create a flat-top window.
	 *
	 * @param size the window size
	 * @return a new WindowComputation for flat-top window
	 */
	public static WindowComputation flattop(int size) {
		return new WindowComputation(Type.FLATTOP, size);
	}
}
