/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.List;

/**
 * A hardware-accelerated computation for multi-order FIR (Finite Impulse Response) filtering
 * of time-series signals with configurable filter coefficients.
 *
 * <p>{@link MultiOrderFilter} applies a convolution operation between the input signal and
 * a set of filter coefficients, implementing smoothing, low-pass, high-pass, band-pass, and
 * other frequency-domain operations. The filter can execute on GPU/accelerator hardware for
 * real-time signal processing.</p>
 *
 * <h2>What is a FIR Filter?</h2>
 * <p>A Finite Impulse Response filter computes each output sample as a weighted sum of
 * the current and previous input samples. The weights are called coefficients, and their
 * count determines the filter order:</p>
 * <pre>
 * y[n] = &Sigma;(i=0 to order) h[i] * x[n - i + order/2]
 *
 * Where:
 * - y[n]: Output sample at position n
 * - h[i]: Filter coefficient at index i
 * - x[n - i + order/2]: Input sample (centered around current position)
 * - order: Number of coefficients - 1
 * </pre>
 *
 * <h2>Common Filter Types</h2>
 *
 * <h3>Low-Pass Filter (Smoothing)</h3>
 * <p>Removes high frequencies, smoothing the signal:</p>
 * <pre>{@code
 * // Simple 3-tap averaging filter
 * double[] coeffs = {1.0/3, 1.0/3, 1.0/3};  // Order 2
 *
 * // Or Gaussian smoothing (order 4)
 * double[] gaussCoeffs = {0.06, 0.24, 0.40, 0.24, 0.06};
 * }</pre>
 *
 * <h3>High-Pass Filter (Edge Detection)</h3>
 * <p>Emphasizes rapid changes, removes DC offset:</p>
 * <pre>{@code
 * // Simple high-pass (order 2)
 * double[] coeffs = {-0.25, 0.5, -0.25};
 * }</pre>
 *
 * <h3>Band-Pass Filter</h3>
 * <p>Isolates frequencies within a specific range.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Smoothing</h3>
 * <pre>{@code
 * // Noisy signal
 * Producer<PackedCollection<?>> signal = p(noisyData);
 *
 * // 5-tap averaging filter
 * PackedCollection<?> coeffs = new PackedCollection<>(5);
 * for (int i = 0; i < 5; i++) {
 *     coeffs.set(i, 0.2);  // Each coefficient: 1/5
 * }
 *
 * MultiOrderFilter smoother = MultiOrderFilter.create(signal, c(coeffs));
 * PackedCollection<?> smoothed = smoother.get().evaluate();
 * }</pre>
 *
 * <h3>Gaussian Smoothing</h3>
 * <pre>{@code
 * // Gaussian kernel (sigma = 1.0)
 * double[] gaussian = {0.06136, 0.24477, 0.38774, 0.24477, 0.06136};
 * PackedCollection<?> kernel = new PackedCollection<>(gaussian.length);
 * for (int i = 0; i < gaussian.length; i++) {
 *     kernel.set(i, gaussian[i]);
 * }
 *
 * MultiOrderFilter gaussianFilter = MultiOrderFilter.create(signal, c(kernel));
 * PackedCollection<?> result = gaussianFilter.get().evaluate();
 * }</pre>
 *
 * <h3>Real-Time Audio Filtering</h3>
 * <pre>{@code
 * // Apply low-pass filter to audio stream
 * AcceleratedTimeSeries audioStream = new AcceleratedTimeSeries(44100);
 *
 * // 11-tap low-pass filter (cutoff ~1kHz at 44.1kHz sample rate)
 * double[] lpCoeffs = designLowPassFilter(11, 1000.0, 44100.0);
 * PackedCollection<?> filterKernel = new PackedCollection<>(lpCoeffs.length);
 * // ... fill kernel
 *
 * MultiOrderFilter lpFilter = MultiOrderFilter.create(p(audioStream), c(filterKernel));
 *
 * // In temporal loop
 * Temporal audioProcessor = () -> {
 *     return lpFilter.get();  // Hardware-accelerated filtering
 * };
 * }</pre>
 *
 * <h3>Batch Processing</h3>
 * <pre>{@code
 * // Filter multiple signals simultaneously
 * PackedCollection<?> batchSignals = new PackedCollection<>(10, 1024);  // 10 signals
 * Producer<PackedCollection<?>> batchP = c(batchSignals);
 *
 * // Same filter applied to all signals
 * MultiOrderFilter batchFilter = MultiOrderFilter.create(batchP, c(coeffs));
 * PackedCollection<?> filteredBatch = batchFilter.get().evaluate();
 * }</pre>
 *
 * <h2>Filter Design Considerations</h2>
 *
 * <h3>Filter Order</h3>
 * <ul>
 *   <li><strong>Low Order (3-7):</strong> Fast, minimal latency, less frequency selectivity</li>
 *   <li><strong>Medium Order (9-21):</strong> Balance of performance and quality</li>
 *   <li><strong>High Order (23-101):</strong> Sharp frequency cutoffs, higher latency</li>
 * </ul>
 *
 * <h3>Coefficient Design</h3>
 * <p>Common methods:</p>
 * <ul>
 *   <li><strong>Windowing:</strong> Apply window function to ideal filter response</li>
 *   <li><strong>Least Squares:</strong> Minimize error vs ideal response</li>
 *   <li><strong>Parks-McClellan:</strong> Optimal equiripple FIR design</li>
 *   <li><strong>Frequency Sampling:</strong> Sample desired frequency response</li>
 * </ul>
 *
 * <h2>Edge Handling</h2>
 * <p>Near signal boundaries, the filter only includes samples that exist in the input.
 * Samples outside the valid range are ignored (effectively zero-padded):</p>
 * <pre>
 * For input of length N:
 * - Samples 0 to order/2: Partial filtering (fewer coefficients)
 * - Samples order/2 to N-order/2: Full filtering (all coefficients)
 * - Samples N-order/2 to N-1: Partial filtering
 * </pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Time Complexity:</strong> O(N * M) where N=signal length, M=filter order</li>
 *   <li><strong>Space Complexity:</strong> O(N) output + O(M) coefficients</li>
 *   <li><strong>Hardware Acceleration:</strong> GPU/OpenCL/Metal compatible</li>
 *   <li><strong>Parallelism:</strong> Each output sample computed independently</li>
 * </ul>
 *
 * <h2>Frequency Response</h2>
 * <p>The filter's effect on different frequencies depends on the coefficients.
 * Analyze frequency response using FFT:</p>
 * <pre>{@code
 * // Pad coefficients to power of 2
 * PackedCollection<?> paddedCoeffs = new PackedCollection<>(2, 512);
 * // Copy coeffs and zero-pad...
 *
 * // Compute FFT to see frequency response
 * FourierTransform fft = new FourierTransform(512, c(paddedCoeffs));
 * PackedCollection<?> freqResponse = fft.get().evaluate();
 *
 * // Magnitude at each frequency shows filter gain
 * for (int i = 0; i < 256; i++) {
 *     double re = freqResponse.toDouble(2*i);
 *     double im = freqResponse.toDouble(2*i + 1);
 *     double magnitude = Math.sqrt(re*re + im*im);
 *     double frequencyHz = (i * sampleRate) / 512.0;
 *     System.out.println(frequencyHz + " Hz: " + magnitude);
 * }
 * }</pre>
 *
 * <h2>Common Applications</h2>
 * <ul>
 *   <li><strong>Audio Processing:</strong> EQ, bass/treble boost, noise reduction</li>
 *   <li><strong>Signal Smoothing:</strong> Remove noise from sensor data</li>
 *   <li><strong>Edge Detection:</strong> Find rapid changes in signals</li>
 *   <li><strong>Resampling:</strong> Anti-aliasing before downsampling</li>
 *   <li><strong>Compression:</strong> Pre-emphasis/de-emphasis</li>
 * </ul>
 *
 * <h2>Comparison with IIR Filters</h2>
 * <table border="1">
 * <caption>Table</caption>
 * <tr><th>Feature</th><th>FIR (This class)</th><th>IIR</th></tr>
 * <tr><td>Stability</td><td>Always stable</td><td>Can become unstable</td></tr>
 * <tr><td>Phase Response</td><td>Linear (no distortion)</td><td>Non-linear</td></tr>
 * <tr><td>Order Required</td><td>Higher (more coeffs)</td><td>Lower</td></tr>
 * <tr><td>Computation</td><td>More multiplies</td><td>Fewer operations</td></tr>
 * <tr><td>Hardware Acceleration</td><td>Excellent</td><td>Limited (feedback)</td></tr>
 * </table>
 *
 * @see FourierTransform
 * @see org.almostrealism.time.TemporalFeatures#lowPass(Producer, Producer, int)
 * @see org.almostrealism.time.TemporalFeatures#highPass(Producer, Producer, int)
 *
 * @author Michael Murray
 */
public class MultiOrderFilter extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	private int filterOrder;

	/**
	 * Constructs a multi-order filter with explicit output shape.
	 *
	 * @param shape The output shape (typically matches series shape)
	 * @param series Producer providing the input signal
	 * @param coefficients Producer providing the filter coefficients
	 * @throws UnsupportedOperationException if series or coefficients have size <= 1
	 */
	public MultiOrderFilter(TraversalPolicy shape, Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> coefficients) {
		super("multiOrderFilter", shape, series, coefficients);

		TraversalPolicy seriesShape = CollectionFeatures.getInstance().shape(series);
		TraversalPolicy coeffShape = CollectionFeatures.getInstance().shape(coefficients);

		if (seriesShape.getSizeLong() <= 1) {
			throw new UnsupportedOperationException();
		}

		if (coeffShape.getSizeLong() <= 1) {
			throw new UnsupportedOperationException();
		}

		this.filterOrder = coeffShape.getSize() - 1;
	}

	@Override
	public Scope<PackedCollection<?>> getScope(KernelStructureContext context) {
		Scope<PackedCollection<?>> scope = super.getScope(context);

		CollectionVariable output = getCollectionArgumentVariable(0);
		CollectionVariable input = getCollectionArgumentVariable(1);
		CollectionVariable coefficients = getCollectionArgumentVariable(2);

		Expression result = scope.declareDouble("result", e(0.0));

		Repeated loop = new Repeated<>();
		{
			InstanceReference i = Variable.integer("i").ref();
			loop.setIndex(i.getReferent());
			loop.setCondition(i.lessThanOrEqual(e(filterOrder)));
			loop.setInterval(e(1));

			Scope<?> body = new Scope<>();
			{
				Expression index = body.declareInteger("index", kernel(context).add(i.subtract(e(filterOrder / 2))));

				Expression coeff = coefficients.getShape().getDimensions() == 1 ?
						coefficients.getValueAt(i) : coefficients.getValue(kernel(), i);

				body.addCase(index.greaterThanOrEqual(e(0)).and(index.lessThan(input.length())),
						result.assign(result.add(input.getValueAt(index).multiply(coeff))));
			}

			loop.add(body);
		}

		scope.add(loop);


		Scope outScope = new Scope();
		{
			outScope.getStatements().add(output.getValueAt(kernel(context)).assign(result));
		}

		scope.add(outScope);

		return scope;
	}

	@Override
	public MultiOrderFilter generate(List<Process<?, ?>> children) {
		return new MultiOrderFilter(getShape(), (Producer) children.get(1), (Producer) children.get(2));
	}

	/**
	 * Factory method to create a filter with automatic shape inference.
	 *
	 * <p>This method infers the output shape from the input series and ensures
	 * proper traversal configuration for filtering. Use this method instead of
	 * the constructor for most cases.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Producer<PackedCollection<?>> signal = p(audioData);
	 * PackedCollection<?> coeffs = new PackedCollection<>(5);
	 * // Fill coeffs...
	 *
	 * MultiOrderFilter filter = MultiOrderFilter.create(signal, c(coeffs));
	 * PackedCollection<?> filtered = filter.get().evaluate();
	 * }</pre>
	 *
	 * @param series Producer providing the input signal
	 * @param coefficients Producer providing the filter coefficients (length = order + 1)
	 * @return A new MultiOrderFilter instance ready for evaluation
	 */
	public static MultiOrderFilter create(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> coefficients) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return new MultiOrderFilter(shape.traverseEach(), series, coefficients);
	}
}
