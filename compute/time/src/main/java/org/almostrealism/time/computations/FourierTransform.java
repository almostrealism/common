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

package org.almostrealism.time.computations;

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.List;

/**
 * A hardware-accelerated computation for performing Fast Fourier Transform (FFT) and Inverse FFT (IFFT)
 * on complex signals, converting them to/from the frequency domain.
 *
 * <p>{@link FourierTransform} compiles an iterative radix-2 Cooley-Tukey FFT into a single kernel.
 * Each kernel thread transforms one complex line of {@code bins} samples: the line is copied into the
 * output in bit-reversed order and then combined in place by {@code log2(bins)} butterfly stages,
 * each of which is an ordinary loop in the generated code. The generated kernel contains no
 * function calls and no recursion, so the same program compiles for every backend, including
 * Metal and OpenCL, which do not support recursion.</p>
 *
 * <h2>Data Format</h2>
 * <p>The input and output are complex numbers stored as interleaved real/imaginary pairs:</p>
 * <pre>
 * Index:  0     1     2     3     4     5     6     7
 * Data:  [re0, im0, re1, im1, re2, im2, re3, im3]
 * </pre>
 *
 * <h2>Lines and Strides</h2>
 * <p>A transform processes {@code count} lines in parallel, one per kernel thread. With a stride of
 * 1 (the default) the lines are contiguous: line {@code l} occupies complex elements
 * {@code [l * bins, (l + 1) * bins)}. With a stride {@code s > 1}, the samples of one line are
 * {@code s} complex elements apart, which is how a transform runs along a non-trailing axis of a
 * multidimensional collection without transposing it: for line {@code l}, sample {@code j} is the
 * complex element</p>
 * <pre>
 * (l / s) * bins * s + (l % s) + j * s
 * </pre>
 * <p>so {@code count} must be a multiple of {@code s}. For a {@code [rows, cols, 2]} collection, the
 * transform along the columns axis uses {@code count = rows, stride = 1}, and the transform along the
 * rows axis uses {@code count = cols, stride = cols}. The output always has the same memory layout as
 * the input; the result shape {@code (count, 2, bins)} only describes its total size and kernel count,
 * and callers that use a stride reshape it back to the shape of the input (see
 * {@code SpectralFeatures#fftAxis(Producer, int, boolean)}).</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Forward transform of 512 complex samples
 * FourierTransform fft = new FourierTransform(512, signal);
 *
 * // Inverse transform of 10 spectra of 1024 bins at once
 * FourierTransform ifft = new FourierTransform(10, 1024, true, spectra);
 * }</pre>
 *
 * <h2>Normalization</h2>
 * <p>Forward: {@code X[k] = sum_n x[n] e^(-2 pi i k n / N)} (unnormalised).
 * Inverse: {@code x[n] = (1/N) sum_k X[k] e^(2 pi i k n / N)}. The inverse therefore exactly undoes
 * the forward transform.</p>
 *
 * <h2>Precision</h2>
 * <p>Every twiddle factor is evaluated directly as {@code cos}/{@code sin} of its own angle rather than
 * by a recurrence, so rounding error does not accumulate across a stage. On an FP64 backend the round
 * trip error is near machine precision; on an FP32 backend (Metal) it grows roughly with
 * {@code log2(N)} single-precision roundings, typically below {@code 1e-5} relative to the signal
 * amplitude.</p>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>The bin count must be a power of 2</li>
 *   <li>Parallelism is across lines; a single line is transformed by one thread</li>
 *   <li>Input must be in complex (real, imaginary) interleaved format</li>
 * </ul>
 *
 * @see TemporalFeatures#fft(int, Producer, io.almostrealism.compute.ComputeRequirement...)
 * @see TemporalFeatures#ifft(int, Producer, io.almostrealism.compute.ComputeRequirement...)
 *
 * @author Michael Murray
 */
public class FourierTransform extends CollectionProducerComputationBase {
	/**
	 * Enables relative indexing for traversal policies.
	 * When true, uses traverse(1) for more efficient memory access patterns.
	 */
	public static boolean enableRelative = true;

	/** Counter used to generate unique variable names within generated kernel scopes. */
	private int varIdx = 0;
	/** The number of complex samples in each transformed line. */
	private final int bins;
	/** The distance, in complex elements, between consecutive samples of one line. */
	private final int stride;
	/** Whether this transform computes the inverse FFT (true) or the forward FFT (false). */
	private final boolean inverse;

	/**
	 * Constructs a Fourier transform for a single signal.
	 *
	 * @param bins Number of frequency bins (must be a power of 2)
	 * @param input Producer providing the complex time-domain signal
	 */
	public FourierTransform(int bins, Producer<PackedCollection> input) {
		this(1, bins, input);
	}

	/**
	 * Constructs a Fourier transform for batch processing multiple signals.
	 *
	 * @param count Number of signals to process in parallel
	 * @param bins Number of frequency bins per signal (must be a power of 2)
	 * @param input Producer providing the batch of complex signals
	 */
	public FourierTransform(int count, int bins, Producer<PackedCollection> input) {
		this(count, bins, false, input);
	}

	/**
	 * Constructs a Fourier transform of contiguous lines with explicit forward/inverse specification.
	 *
	 * @param count Number of signals to process in parallel
	 * @param bins Number of frequency bins (must be a power of 2)
	 * @param inverse If true, performs inverse FFT; if false, performs forward FFT
	 * @param input Producer providing the input signals (time-domain if !inverse, frequency-domain if inverse)
	 */
	public FourierTransform(int count, int bins, boolean inverse, Producer<PackedCollection> input) {
		this(count, bins, 1, inverse, input);
	}

	/**
	 * Constructs a Fourier transform of {@code count} lines whose samples are {@code stride}
	 * complex elements apart.
	 *
	 * @param count   Number of lines to process in parallel; must be a multiple of {@code stride}
	 * @param bins    Number of complex samples in each line (must be a power of 2)
	 * @param stride  Distance, in complex elements, between consecutive samples of a line
	 * @param inverse If true, performs inverse FFT; if false, performs forward FFT
	 * @param input   Producer providing {@code count * bins} interleaved complex values
	 * @throws IllegalArgumentException if {@code bins} is not a power of 2, or {@code count} is not
	 *                                  a positive multiple of {@code stride}
	 */
	public FourierTransform(int count, int bins, int stride, boolean inverse, Producer<PackedCollection> input) {
		super(inverse ? "fourierTransformInverse"  : "fourierTransform",
				enableRelative ?
						new TraversalPolicy(count, 2, bins).traverse(1) :
						new TraversalPolicy(count, 2, bins),
				input);

		if (bins < 1 || Integer.bitCount(bins) != 1) {
			throw new IllegalArgumentException("FFT length " + bins + " is not a power of 2");
		}

		if (stride < 1 || count < 1 || count % stride != 0) {
			throw new IllegalArgumentException("Line count " + count +
					" is not a positive multiple of stride " + stride);
		}

		this.bins = bins;
		this.stride = stride;
		this.inverse = inverse;
		init();
	}

	/** Returns the number of complex samples in each transformed line. */
	public int getBins() { return bins; }

	/** Returns the distance, in complex elements, between consecutive samples of one line. */
	public int getStride() { return stride; }

	/** Returns true if this is an inverse transform. */
	public boolean isInverse() { return inverse; }

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "FourierTransform"));

		ArrayVariable<Double> output = getArgument(0);
		CollectionVariable<?> input = getCollectionArgumentVariable(1);

		Expression<Integer> line = kernel(context);
		Expression first = stride == 1 ?
				scope.declareInteger("lineStart_" + varIdx++, line.multiply(bins)) :
				scope.declareInteger("lineStart_" + varIdx++,
						line.divide(stride).multiply(bins * stride).add(line.imod(stride)));

		scope.add(bitReversedCopy(output, input, first));

		int stages = Integer.numberOfTrailingZeros(bins);
		for (int s = 0; s < stages; s++) {
			double scale = inverse && s == stages - 1 ? 1.0 / bins : 1.0;
			scope.add(butterflyStage(output, first, 1 << s, scale));
		}

		return scope;
	}

	/**
	 * Returns the position, in the underlying interleaved data, of the real part of sample
	 * {@code sample} of the line beginning at complex element {@code first}.
	 *
	 * @param first  the complex element index of the first sample of the line
	 * @param sample the sample index within the line
	 * @return the data index of the real part of that sample
	 */
	protected Expression position(Expression first, Expression sample) {
		return first.add(sample.multiply(stride)).multiply(2);
	}

	/**
	 * Returns the bit reversal of {@code index} over {@code log2(bins)} bits, as a single
	 * integer expression with one term per bit.
	 *
	 * @param index the sample index to reverse
	 * @return an expression for the bit-reversed index
	 */
	protected Expression reverseBits(Expression index) {
		int bits = Integer.numberOfTrailingZeros(bins);
		Expression result = e(0);

		for (int b = 0; b < bits; b++) {
			Expression bit = index.divide(e(1 << b)).imod(2);
			result = result.add(bit.multiply(e(1 << (bits - 1 - b))));
		}

		return result;
	}

	/**
	 * Generates the loop that copies the input line into the output in bit-reversed order,
	 * which is the starting arrangement of the in-place iterative FFT. The input is read through
	 * {@link CollectionVariable#getOrderedReference(Expression)}, so an input whose shape carries a
	 * {@link io.almostrealism.collect.TraversalOrdering} is transformed in its logical order.
	 *
	 * @param output the output argument
	 * @param input  the input argument
	 * @param first  the complex element index of the first sample of this thread's line
	 * @return the copy loop
	 */
	protected Repeated bitReversedCopy(ArrayVariable<Double> output, CollectionVariable<?> input,
										  Expression first) {
		InstanceReference i = Variable.integer("i_" + varIdx++).ref();
		Repeated copy = new Repeated<>(i.getReferent(), i.lessThan(e(bins)));

		Scope body = new Scope<>();
		Expression src = body.declareInteger("src_" + varIdx++, position(first, i));
		Expression dst = body.declareInteger("dst_" + varIdx++, position(first, reverseBits(i)));
		body.assign(output.reference(dst), input.getOrderedReference(src));
		body.assign(output.reference(dst.add(1)), input.getOrderedReference(src.add(1)));
		copy.add(body);
		return copy;
	}

	/**
	 * Generates one radix-2 butterfly stage over the output line. Each of the {@code bins / 2}
	 * iterations combines the pair of samples {@code half} apart within a block of {@code 2 * half}
	 * samples, using the twiddle factor {@code e^(-+ i pi p / half)} for its position {@code p} in
	 * the block.
	 *
	 * @param output the output argument, transformed in place
	 * @param first  the complex element index of the first sample of this thread's line
	 * @param half   half the block size of this stage
	 * @param scale  a factor applied to both butterfly results (1/N on the last inverse stage)
	 * @return the stage loop
	 */
	protected Repeated butterflyStage(ArrayVariable<Double> output, Expression first,
										 int half, double scale) {
		InstanceReference j = Variable.integer("j_" + varIdx++).ref();
		Repeated stage = new Repeated<>(j.getReferent(), j.lessThan(e(bins / 2)));

		Scope body = new Scope<>();
		Expression p = body.declareInteger("p_" + varIdx++, j.imod(half));
		Expression top = body.declareInteger("top_" + varIdx++,
				position(first, j.divide(half).multiply(2 * half).add(p)));
		Expression bottom = body.declareInteger("bottom_" + varIdx++,
				position(first, j.divide(half).multiply(2 * half).add(p).add(half)));

		double direction = inverse ? Math.PI : -Math.PI;
		Expression angle = body.declareDouble("angle_" + varIdx++, e(direction / half).multiply(p));
		Expression wr = body.declareDouble("wr_" + varIdx++, angle.cos());
		Expression wi = body.declareDouble("wi_" + varIdx++, angle.sin());

		Expression ur = body.declareDouble("ur_" + varIdx++, output.reference(top));
		Expression ui = body.declareDouble("ui_" + varIdx++, output.reference(top.add(1)));
		Expression xr = body.declareDouble("xr_" + varIdx++, output.reference(bottom));
		Expression xi = body.declareDouble("xi_" + varIdx++, output.reference(bottom.add(1)));

		Expression tr = body.declareDouble("tr_" + varIdx++, wr.multiply(xr).subtract(wi.multiply(xi)));
		Expression ti = body.declareDouble("ti_" + varIdx++, wr.multiply(xi).add(wi.multiply(xr)));

		body.assign(output.reference(top), scaled(ur.add(tr), scale));
		body.assign(output.reference(top.add(1)), scaled(ui.add(ti), scale));
		body.assign(output.reference(bottom), scaled(ur.subtract(tr), scale));
		body.assign(output.reference(bottom.add(1)), scaled(ui.subtract(ti), scale));

		stage.add(body);
		return stage;
	}

	/**
	 * Returns {@code value} multiplied by {@code scale}, or {@code value} itself when the
	 * scale is 1.
	 *
	 * @param value the value to scale
	 * @param scale the scale factor
	 * @return the scaled expression
	 */
	private Expression scaled(Expression value, double scale) {
		return scale == 1.0 ? value : value.multiply(e(scale));
	}

	@Override
	public FourierTransform generate(List<Process<?, ?>> children) {
		return new FourierTransform(getShape().length(0), getShape().length(2), stride, inverse,
				(Producer) children.get(1));
	}

	/**
	 * Extends the signature with the stride, since transforms of the same shape and direction
	 * but different strides address their data differently and must not share a compiled kernel.
	 *
	 * @return a signature string including the stride, or null if the parent signature is null
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;
		return signature + "{stride=" + stride + "}";
	}
}
