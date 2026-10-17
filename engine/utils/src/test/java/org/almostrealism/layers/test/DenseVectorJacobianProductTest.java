/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Function;

/**
 * Verifies that the {@link ReduceAxis reduced} delta of a dense layer's operator produces
 * exactly the gradient that contracting the operator's full Jacobian produces, and that,
 * where every computation of the operator knows its own reduced delta, it does so without
 * forming anything as large as that Jacobian.
 *
 * <p>Every comparison is made on the whole dense operator built by
 * {@link LayerFeatures#denseOperator} (output reshape of the optional bias addition of the
 * matrix multiply), so a reduction that fails to pass through those wrappers is caught. The
 * side under test is the {@link ReduceAxis#OUT output-reduced} delta weighted by the upstream
 * gradient, which the backward pass uses; the
 * full-Jacobian reference is {@link #jacobianGradient}, which materializes the Jacobian
 * regardless of the structure of the operator, and for dense operators both are also
 * compared with the gradient computed on the host from its definition. Inputs are sequences
 * of rows, so the weight gradient must reduce the per-row outer products over every
 * position. The {@link ReduceAxis#IN input-reduced} delta is checked against the
 * Jacobian-vector product computed on the host.</p>
 *
 * @author Michael Murray
 */
public class DenseVectorJacobianProductTest extends TestSuiteBase implements LayerFeatures {
	/** Number of sequence positions, matching the documentation language model. */
	private static final int SEQ_LEN = 64;

	/** Width of the rows fed to each projection, matching the documentation language model. */
	private static final int DIM = 64;

	/**
	 * The output projection (256 x 64) gradients agree with the gradients computed from
	 * their definition, and the input gradient agrees with the full Jacobian.
	 *
	 * <p>The full-Jacobian weight gradient is not a usable reference at this size: on Metal
	 * it has been measured to disagree with the definition in roughly a tenth of the 16,384
	 * entries (from row 64 onward), by a different count on each run. The comparison is
	 * still computed and the number of disagreeing entries is logged, but only the agreement
	 * of the reduced delta with the definition is asserted for the weight.</p>
	 */
	@Test(timeout = 20 * 60000)
	public void outputProjection() {
		assertDenseGradientsMatch(shape(1, SEQ_LEN, DIM), 4 * DIM, false);
	}

	/** The fused query/key/value projection (192 x 64) agrees with the full Jacobian. */
	@Test(timeout = 20 * 60000)
	public void fusedQkvProjection() {
		assertDenseGradientsMatch(shape(1, SEQ_LEN, DIM), 3 * DIM);
	}

	/** The attention output projection (64 x 64) agrees with the full Jacobian. */
	@Test(timeout = 20 * 60000)
	public void attentionOutputProjection() {
		assertDenseGradientsMatch(shape(1, SEQ_LEN, DIM), DIM);
	}

	/**
	 * The feed-forward down projection (64 x 128), whose input is wider than its output,
	 * agrees with the full Jacobian.
	 */
	@Test(timeout = 20 * 60000)
	public void feedForwardDownProjection() {
		assertDenseGradientsMatch(shape(1, SEQ_LEN, 2 * DIM), DIM);
	}

	/** A feed-forward projection (128 x 64) agrees with the full Jacobian. */
	@Test(timeout = 20 * 60000)
	public void feedForwardProjection() {
		assertDenseGradientsMatch(shape(1, SEQ_LEN, DIM), 2 * DIM);
	}

	/**
	 * With a bias, the weight, input and bias gradients are all reduced through the bias
	 * addition and agree with the full Jacobian; the bias, repeated for every row, has the
	 * gradient summed over the rows.
	 */
	@Test(timeout = 10 * 60000)
	public void denseWithBias() {
		assertDenseWithBiasGradientsMatch(16, 24, 40);
	}

	/**
	 * With a bias and as many rows as output nodes, the bias is still added per node rather
	 * than per row, and the gradients agree with the full Jacobian.
	 */
	@Test(timeout = 10 * 60000)
	public void denseWithBiasRowsMatchingNodes() {
		assertDenseWithBiasGradientsMatch(24, 16, 24);
	}

	/**
	 * Builds a dense operator with a bias, checks its forward pass against the definition
	 * {@code y_r = W · x_r + b}, and checks the weight, input and bias gradients against
	 * the full Jacobian.
	 *
	 * @param rows  the number of input rows
	 * @param size  the input width
	 * @param nodes the output width
	 */
	private void assertDenseWithBiasGradientsMatch(int rows, int size, int nodes) {
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection biases = new PackedCollection(shape(nodes)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection gradient = new PackedCollection(shape(1, rows, nodes)).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer out = (CollectionProducer) denseOperator(inputShape, weights, biases).getResultant(in);

		double[] w = weights.toArray(0, nodes * size);
		double[] b = biases.toArray(0, nodes);
		double[] x = input.toArray(0, rows * size);
		double[] output = new double[rows * nodes];
		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < nodes; i++) {
				output[r * nodes + i] = b[i];
				for (int j = 0; j < size; j++) {
					output[r * nodes + i] += w[i * size + j] * x[r * size + j];
				}
			}
		}

		assertValuesMatch("forward", output, evaluate(out).toArray(0, rows * nodes));

		assertGradientsMatch(out, p(weights), cp(gradient), true);
		assertGradientsMatch(out, in, cp(gradient), true);
		assertGradientsMatch(out, p(biases), cp(gradient), true);
	}

	/** A single input vector (no sequence axis) agrees with the full Jacobian. */
	@Test(timeout = 5 * 60000)
	public void singleVector() {
		assertDenseGradientsMatch(shape(24), 40);
	}

	/**
	 * Degenerate dense layers with a single output node, over several rows and over a
	 * single row, agree with the full Jacobian.
	 */
	@Test(timeout = 5 * 60000)
	public void singleOutputNode() {
		assertDenseGradientsMatch(shape(1, 5, 3), 1, true, false);
		assertDenseGradientsMatch(shape(3), 1, true, false);
	}

	/**
	 * An output wider than 1000 lowers the matrix multiply to a weighted sum rather than a
	 * multiply and sum. The weighted sum's output-reduced delta is the transposed weighted
	 * sum of the upstream gradient with the other operand, so its gradients are formed
	 * without the Jacobian and must agree with the definition and with the Jacobian.
	 */
	@Test(timeout = 10 * 60000)
	public void wideOutputWeightedSum() {
		assertDenseGradientsMatch(shape(1, 3, 8), 1200, true, true);
	}

	/**
	 * The {@link ReduceAxis#IN input-reduced} delta of a weighted sum, as a matrix multiply
	 * wider than 1000 outputs lowers to, is the weighted sum of each operand's tangent with
	 * the other operand: {@code Σ_j T[i][j] x_r[j]} for a tangent {@code T} of the weight and
	 * {@code Σ_j W[i][j] X_r[j]} for a tangent {@code X} of the input.
	 */
	@Test(timeout = 10 * 60000)
	public void wideOutputWeightedSumTangent() {
		int rows = 3;
		int size = 8;
		int nodes = 1200;
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection weightTangent = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection inputTangent = new PackedCollection(inputShape).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer out = (CollectionProducer) denseOperator(inputShape, weights, null).getResultant(in);

		double[] w = weights.toArray(0, nodes * size);
		double[] x = input.toArray(0, rows * size);
		double[] tw = weightTangent.toArray(0, nodes * size);
		double[] tx = inputTangent.toArray(0, rows * size);
		double[] weightProduct = new double[rows * nodes];
		double[] inputProduct = new double[rows * nodes];
		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < nodes; i++) {
				for (int j = 0; j < size; j++) {
					weightProduct[r * nodes + i] += tw[i * size + j] * x[r * size + j];
					inputProduct[r * nodes + i] += w[i * size + j] * tx[r * size + j];
				}
			}
		}

		assertTangentMatches(out, p(weights), cp(weightTangent), weightProduct);
		assertTangentMatches(out, in, cp(inputTangent), inputProduct);
	}

	/**
	 * An element-wise product is reduced through the product rule, and its gradient with
	 * respect to either operand agrees with the full Jacobian. An activation applied to a
	 * matrix multiply, which is differentiated through the chain rule, does too.
	 */
	@Test(timeout = 5 * 60000)
	public void elementwiseAndActivation() {
		PackedCollection weights = new PackedCollection(shape(8, 6)).randnFill();
		PackedCollection input = new PackedCollection(shape(8, 6)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(8, 6)).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer elementwise = cp(input).multiply(p(weights));
		assertGradientsMatch(elementwise, p(weights), cp(gradient), true);
		assertGradientsMatch(elementwise, in, cp(gradient), true);

		CollectionProducer activated = sigmoid(matmul(p(weights), cp(input).traverse(1)));
		assertGradientsMatch(activated, p(weights), cp(new PackedCollection(shape(8, 8)).randnFill()), false);
	}

	/** A genuine matrix-by-matrix product agrees with the full Jacobian. */
	@Test(timeout = 5 * 60000)
	public void matrixMatrix() {
		PackedCollection a = new PackedCollection(shape(5, 4)).randnFill();
		PackedCollection b = new PackedCollection(shape(4, 3)).randnFill();
		CollectionProducer product = matmul(p(a), traverse(0, p(b)));
		Assert.assertEquals(15, product.getShape().getTotalSize());
		assertGradientsMatch(product, p(a), cp(new PackedCollection(shape(5, 3)).randnFill()), false);
	}

	/**
	 * When the rows are computed from the target rather than being the target, the
	 * reduction is passed on through that computation, and the gradient with respect to
	 * the target agrees with the full Jacobian. The weight, which the rows cannot depend
	 * on, is still reduced without forming the Jacobian.
	 */
	@Test(timeout = 5 * 60000)
	public void targetFeedingRows() {
		TraversalPolicy inputShape = shape(1, 5, 6);
		PackedCollection weights = new PackedCollection(shape(7, 6)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection gradient = new PackedCollection(shape(1, 5, 7)).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer out = (CollectionProducer) denseOperator(inputShape, weights, null)
				.getResultant(sigmoid(in));

		assertGradientsMatch(out, in, cp(gradient), false);
		assertGradientsMatch(out, p(weights), cp(gradient), true);
	}

	/**
	 * A matrix multiplied by its own rows ({@code y_r = W · w_r}) has the target in both
	 * operands, and the product rule accounts for both, so the gradient agrees with its
	 * definition {@code dW[a][b] = Σ_r g[r][a] W[r][b] + Σ_i g[a][i] W[i][b]}.
	 */
	@Test(timeout = 5 * 60000)
	public void matrixInBothOperands() {
		int n = 4;
		PackedCollection weights = new PackedCollection(shape(n, n)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(n, n)).randnFill();

		CollectionProducer out = matmul(p(weights), traverse(1, p(weights)));
		Assert.assertEquals(n * n, out.getShape().getTotalSize());

		double[] w = weights.toArray(0, n * n);
		double[] g = gradient.toArray(0, n * n);
		double[] reference = new double[n * n];
		for (int a = 0; a < n; a++) {
			for (int b = 0; b < n; b++) {
				for (int k = 0; k < n; k++) {
					reference[a * n + b] += g[k * n + a] * w[k * n + b] + g[a * n + k] * w[k * n + b];
				}
			}
		}

		assertGradientsMatch(out, p(weights), cp(gradient), false, reference, true);
	}

	/**
	 * A residual connection {@code y = dense(x) + x}, where {@code x} is neither a value
	 * source nor has any inputs, has the target as an operand of the sum. That operand
	 * depends on the target although no input of it contains the target, and its
	 * contribution {@code g_r} must be kept alongside the dense term's {@code Wᵀ · g_r}.
	 */
	@Test(timeout = 5 * 60000)
	public void residualOfDynamicInput() {
		int rows = 3;
		int size = 4;
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(size, size)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection gradient = new PackedCollection(inputShape).randnFill();
		Function<Object[], PackedCollection> function = args -> input;

		CollectionProducer x = new DynamicCollectionProducer(inputShape, function);
		CollectionProducer dense = (CollectionProducer) denseOperator(inputShape, weights, null).getResultant(x);

		double[] w = weights.toArray(0, size * size);
		double[] g = gradient.toArray(0, rows * size);
		double[] reference = new double[rows * size];
		for (int r = 0; r < rows; r++) {
			for (int j = 0; j < size; j++) {
				reference[r * size + j] = g[r * size + j];
				for (int i = 0; i < size; i++) {
					reference[r * size + j] += g[r * size + i] * w[i * size + j];
				}
			}
		}

		PackedCollection actual = evaluate(dense.add(x).delta(x, ReduceAxis.OUT, cp(gradient)));
		assertValuesMatch("residual", reference, actual.toArray(0, rows * size));
		actual.destroy();
	}

	/**
	 * The sum of two dense operators sharing a weight matrix reduces the delta for that
	 * matrix through both terms, {@code dW = Σ_r g_r ⊗ x_r + Σ_r g_r ⊗ z_r}, and for each
	 * input through the one term it appears in.
	 */
	@Test(timeout = 10 * 60000)
	public void sharedWeightSumAddsTermProducts() {
		int rows = 5;
		int size = 6;
		int nodes = 7;
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection first = new PackedCollection(inputShape).randnFill();
		PackedCollection second = new PackedCollection(inputShape).randnFill();
		PackedCollection gradient = new PackedCollection(shape(1, rows, nodes)).randnFill();

		Producer<PackedCollection> x = cp(first);
		Producer<PackedCollection> z = cp(second);
		CollectionProducer out = ((CollectionProducer) denseOperator(inputShape, weights, null).getResultant(x))
				.add(denseOperator(inputShape, weights, null).getResultant(z));

		double[] w = weights.toArray(0, nodes * size);
		double[] xv = first.toArray(0, rows * size);
		double[] zv = second.toArray(0, rows * size);
		double[] g = gradient.toArray(0, rows * nodes);
		double[] weightGradient = new double[nodes * size];
		double[] inputGradient = new double[rows * size];
		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < nodes; i++) {
				for (int j = 0; j < size; j++) {
					weightGradient[i * size + j] += g[r * nodes + i] * (xv[r * size + j] + zv[r * size + j]);
					inputGradient[r * size + j] += g[r * nodes + i] * w[i * size + j];
				}
			}
		}

		assertGradientsMatch(out, p(weights), cp(gradient), true, weightGradient, true);
		assertGradientsMatch(out, x, cp(gradient), true, inputGradient, true);
		assertGradientsMatch(out, z, cp(gradient), true, inputGradient, true);
	}

	/**
	 * A sum with a single value that depends on the target and is broadcast into every
	 * output gives that value the total of the upstream gradient, so the gradient of
	 * {@code dense(x) + Σ W} with respect to {@code W} is {@code Σ_r g_r ⊗ x_r + Σ g},
	 * formed without the Jacobian; a sum that does not depend on the target has a zero
	 * gradient.
	 */
	@Test(timeout = 5 * 60000)
	public void sumWithBroadcastAndIndependentOperands() {
		int rows = 3;
		int size = 4;
		int nodes = 2;
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection gradient = new PackedCollection(shape(1, rows, nodes)).randnFill();

		CollectionProducer dense = (CollectionProducer) denseOperator(inputShape, weights, null).getResultant(cp(input));
		CollectionProducer broadcast = dense.add(sum(p(weights)));
		Assert.assertTrue(broadcast instanceof CollectionAddComputation);

		double[] x = input.toArray(0, rows * size);
		double[] g = gradient.toArray(0, rows * nodes);
		double total = 0.0;
		for (double v : g) total += v;

		double[] reference = new double[nodes * size];
		for (int i = 0; i < nodes; i++) {
			for (int j = 0; j < size; j++) {
				reference[i * size + j] = total;
				for (int r = 0; r < rows; r++) {
					reference[i * size + j] += g[r * nodes + i] * x[r * size + j];
				}
			}
		}

		CollectionProducer reduced = broadcast.delta(p(weights), ReduceAxis.OUT, cp(gradient));
		assertNoLargerThanForward(broadcast, reduced);
		PackedCollection actual = evaluate(reduced);
		assertValuesMatch("broadcast", reference, actual.toArray(0, nodes * size));
		actual.destroy();

		CollectionProducer independent = cp(input).add(cp(input));
		PackedCollection zero = evaluate(independent.delta(p(weights), ReduceAxis.OUT, cp(new PackedCollection(inputShape))));
		assertValuesMatch("independent", new double[nodes * size], zero.toArray(0, nodes * size));
		zero.destroy();
	}

	/**
	 * The {@link ReduceAxis#IN input-reduced} delta of a dense operator with a bias is the
	 * Jacobian-vector product: for a tangent {@code T} of the weight it is
	 * {@code Σ_j T[i][j] x_r[j]}, for a tangent {@code X} of the input it is
	 * {@code Σ_j W[i][j] X_r[j]}, and for a tangent {@code B} of the bias it is {@code B[i]}
	 * on every row, each in the shape of the output.
	 */
	@Test(timeout = 10 * 60000)
	public void jacobianVectorProduct() {
		int rows = 6;
		int size = 5;
		int nodes = 4;
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection biases = new PackedCollection(shape(nodes)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection weightTangent = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection inputTangent = new PackedCollection(inputShape).randnFill();
		PackedCollection biasTangent = new PackedCollection(shape(nodes)).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer out = (CollectionProducer) denseOperator(inputShape, weights, biases).getResultant(in);

		double[] w = weights.toArray(0, nodes * size);
		double[] x = input.toArray(0, rows * size);
		double[] tw = weightTangent.toArray(0, nodes * size);
		double[] tx = inputTangent.toArray(0, rows * size);
		double[] tb = biasTangent.toArray(0, nodes);
		double[] weightProduct = new double[rows * nodes];
		double[] inputProduct = new double[rows * nodes];
		double[] biasProduct = new double[rows * nodes];
		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < nodes; i++) {
				biasProduct[r * nodes + i] = tb[i];
				for (int j = 0; j < size; j++) {
					weightProduct[r * nodes + i] += tw[i * size + j] * x[r * size + j];
					inputProduct[r * nodes + i] += w[i * size + j] * tx[r * size + j];
				}
			}
		}

		assertTangentMatches(out, p(weights), cp(weightTangent), weightProduct);
		assertTangentMatches(out, in, cp(inputTangent), inputProduct);
		assertTangentMatches(out, p(biases), cp(biasTangent), biasProduct);
	}

	/**
	 * Without a weight, the reduced delta weights every entry along the reduced axis by
	 * one: the output-reduced delta of a dense operator with respect to its input is
	 * {@code Σ_i W[i][j]} for every row, and its input-reduced delta with respect to its
	 * weight is {@code Σ_j x_r[j]} for every node.
	 */
	@Test(timeout = 5 * 60000)
	public void unweightedReduction() {
		int rows = 3;
		int size = 4;
		int nodes = 5;
		TraversalPolicy inputShape = shape(1, rows, size);
		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer out = (CollectionProducer) denseOperator(inputShape, weights, null).getResultant(in);

		double[] w = weights.toArray(0, nodes * size);
		double[] x = input.toArray(0, rows * size);
		double[] columnSums = new double[rows * size];
		double[] rowSums = new double[rows * nodes];
		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < nodes; i++) {
				for (int j = 0; j < size; j++) {
					columnSums[r * size + j] += w[i * size + j];
					rowSums[r * nodes + i] += x[r * size + j];
				}
			}
		}

		PackedCollection outReduced = evaluate(out.delta(in, ReduceAxis.OUT));
		assertValuesMatch("unweighted out", columnSums, outReduced.toArray(0, rows * size));
		outReduced.destroy();

		PackedCollection inReduced = evaluate(out.delta(p(weights), ReduceAxis.IN));
		assertValuesMatch("unweighted in", rowSums, inReduced.toArray(0, rows * nodes));
		inReduced.destroy();
	}

	/**
	 * Builds a dense operator without bias for the given input shape and output width,
	 * and checks the weight and input gradients against their definition and against
	 * the full Jacobian, expecting neither to form anything as large as the Jacobian.
	 *
	 * @param inputShape the input shape, whose last dimension is the input width
	 * @param nodes      the output width
	 */
	private void assertDenseGradientsMatch(TraversalPolicy inputShape, int nodes) {
		assertDenseGradientsMatch(inputShape, nodes, true);
	}

	/**
	 * Builds a dense operator without bias, as {@link #assertDenseGradientsMatch(TraversalPolicy, int)}
	 * does, optionally without requiring the full-Jacobian weight gradient to agree.
	 *
	 * @param inputShape             the input shape, whose last dimension is the input width
	 * @param nodes                  the output width
	 * @param weightJacobianReliable whether the full-Jacobian weight gradient is required to
	 *                               agree with the definition at this size
	 */
	private void assertDenseGradientsMatch(TraversalPolicy inputShape, int nodes, boolean weightJacobianReliable) {
		assertDenseGradientsMatch(inputShape, nodes, weightJacobianReliable, true);
	}

	/**
	 * Builds a dense operator without bias for the given input shape and output width,
	 * and checks the weight and input gradients against their definition, computed on the
	 * host as {@code dW = Σ_r g_r ⊗ x_r} and {@code dx_r = Wᵀ · g_r}. The input gradient
	 * is always also checked against the full Jacobian; the weight gradient is only when
	 * {@code weightJacobianReliable} is set.
	 *
	 * @param inputShape             the input shape, whose last dimension is the input width
	 * @param nodes                  the output width
	 * @param weightJacobianReliable whether the full-Jacobian weight gradient is required to
	 *                               agree with the definition at this size
	 * @param expectReduced          whether the gradients must be formed without anything
	 *                               as large as the Jacobian
	 */
	private void assertDenseGradientsMatch(TraversalPolicy inputShape, int nodes,
										   boolean weightJacobianReliable, boolean expectReduced) {
		int size = inputShape.length(inputShape.getDimensions() - 1);
		TraversalPolicy outputShape = inputShape.replaceDimension(inputShape.getDimensions() - 1, nodes);

		PackedCollection weights = new PackedCollection(shape(nodes, size)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection gradient = new PackedCollection(outputShape).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer out = (CollectionProducer) denseOperator(inputShape, weights, null).getResultant(in);
		Assert.assertEquals(outputShape.getTotalSize(), out.getShape().getTotalSize());

		int rows = inputShape.getTotalSize() / size;
		double[] w = weights.toArray(0, nodes * size);
		double[] x = input.toArray(0, rows * size);
		double[] g = gradient.toArray(0, rows * nodes);
		double[] output = new double[rows * nodes];
		double[] weightGradient = new double[nodes * size];
		double[] inputGradient = new double[rows * size];

		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < nodes; i++) {
				for (int j = 0; j < size; j++) {
					output[r * nodes + i] += w[i * size + j] * x[r * size + j];
					weightGradient[i * size + j] += g[r * nodes + i] * x[r * size + j];
					inputGradient[r * size + j] += g[r * nodes + i] * w[i * size + j];
				}
			}
		}

		assertValuesMatch("forward", output, evaluate(out).toArray(0, rows * nodes));

		assertGradientsMatch(out, p(weights), cp(gradient), expectReduced, weightGradient, weightJacobianReliable);
		assertGradientsMatch(out, in, cp(gradient), expectReduced, inputGradient, true);
	}

	/**
	 * Asserts that the gradient the backward pass uses equals the full-Jacobian gradient,
	 * and, when expected, that it was formed without anything as large as the Jacobian.
	 *
	 * @param function      the forward operator
	 * @param target        the producer to differentiate with respect to
	 * @param gradient      the upstream gradient
	 * @param expectReduced whether the gradient must be formed without the Jacobian
	 */
	private void assertGradientsMatch(CollectionProducer function, Producer<PackedCollection> target,
									  Producer<PackedCollection> gradient, boolean expectReduced) {
		assertGradientsMatch(function, target, gradient, expectReduced, null, true);
	}

	/**
	 * Asserts that, when expected, the gradient was formed without anything as large as the
	 * Jacobian, that it agrees with an independently computed reference when one is given,
	 * and that it agrees with the full-Jacobian gradient. When the full-Jacobian gradient is
	 * not reliable, its disagreement with the reference is logged instead of asserted.
	 *
	 * @param function         the forward operator
	 * @param target           the producer to differentiate with respect to
	 * @param gradient         the upstream gradient
	 * @param expectReduced    whether the gradient must be formed without the Jacobian
	 * @param reference        the gradient computed directly from its definition, or null
	 * @param jacobianReliable whether the full-Jacobian gradient is required to agree
	 */
	private void assertGradientsMatch(CollectionProducer function, Producer<PackedCollection> target,
									  Producer<PackedCollection> gradient, boolean expectReduced,
									  double[] reference, boolean jacobianReliable) {
		CollectionProducer reduced = function.delta(target, ReduceAxis.OUT, gradient);

		int size = shape(target).getTotalSize();
		if (expectReduced) {
			assertNoLargerThanForward(function, reduced);
		}

		PackedCollection actual = evaluate(reduced);
		PackedCollection expected = evaluate(jacobianGradient(function, target, gradient));

		Assert.assertEquals(size, actual.getShape().getTotalSize());
		Assert.assertEquals(size, expected.getShape().getTotalSize());

		double[] a = actual.toArray(0, size);
		double[] e = expected.toArray(0, size);
		actual.destroy();
		expected.destroy();

		if (reference != null) {
			assertValuesMatch("reduced", reference, a);
		}

		if (jacobianReliable) {
			if (reference != null) assertValuesMatch("jacobian", reference, e);
			assertValuesMatch("reduced against jacobian", e, a);
		} else {
			int disagreements = 0;
			for (int i = 0; i < size; i++) {
				if (Math.abs(e[i] - reference[i]) > tolerance(reference)) disagreements++;
			}

			log("jacobianDisagreements=" + disagreements + " of " + size);
		}
	}

	/**
	 * Asserts that the {@link ReduceAxis#IN input-reduced} delta of a function for the
	 * given tangent has the shape of the function and agrees with the reference.
	 *
	 * @param function  the forward operator
	 * @param target    the producer to differentiate with respect to
	 * @param tangent   the tangent of the target
	 * @param reference the Jacobian-vector product computed from its definition
	 */
	private void assertTangentMatches(CollectionProducer function, Producer<PackedCollection> target,
									  Producer<PackedCollection> tangent, double[] reference) {
		CollectionProducer product = function.delta(target, ReduceAxis.IN, tangent);
		Assert.assertEquals(function.getShape().getTotalSize(), product.getShape().getTotalSize());

		PackedCollection actual = evaluate(product);
		assertValuesMatch("tangent", reference, actual.toArray(0, reference.length));
		actual.destroy();
	}

	/**
	 * Asserts that no computation in the graph of a reduced delta is larger than the
	 * largest computation of the function it differentiates, which is how a reduced delta
	 * shows that it was formed without the Jacobian (whose size is the product of the
	 * output and target sizes).
	 *
	 * @param function the function being differentiated
	 * @param reduced  its reduced delta
	 */
	private static void assertNoLargerThanForward(CollectionProducer function, CollectionProducer reduced) {
		long forward = largestComputation(function, Collections.newSetFromMap(new IdentityHashMap<>()));
		long largest = largestComputation(reduced, Collections.newSetFromMap(new IdentityHashMap<>()));
		Assert.assertTrue("largest=" + largest + " forward=" + forward, largest <= forward);
	}

	/**
	 * Returns the largest output of any computation in the graph of the given producer.
	 *
	 * @param node    the producer or process to inspect
	 * @param visited the nodes already inspected
	 * @return the largest total size of any collection producer in the graph
	 */
	private static long largestComputation(Object node, Set<Object> visited) {
		if (node == null || !visited.add(node)) return 0;

		long largest = node instanceof CollectionProducer ?
				((CollectionProducer) node).getShape().getTotalSizeLong() : 0;

		if (node instanceof Process) {
			for (Object child : ((Process<?, ?>) node).getChildren()) {
				largest = Math.max(largest, largestComputation(child, visited));
			}
		}

		return largest;
	}

	/**
	 * Returns the absolute tolerance for single-precision agreement with the given values.
	 *
	 * @param expected the expected values
	 * @return the tolerance, relative to their largest magnitude
	 */
	private static double tolerance(double[] expected) {
		double scale = 0.0;
		for (double v : expected) scale = Math.max(scale, Math.abs(v));
		return 1e-5 * Math.max(1.0, scale);
	}

	/**
	 * Asserts that two gradients agree to single-precision rounding, relative to the
	 * largest magnitude of the expected values.
	 *
	 * @param label    describes the comparison in a failure message
	 * @param expected the expected values
	 * @param actual   the actual values
	 */
	private void assertValuesMatch(String label, double[] expected, double[] actual) {
		Assert.assertEquals(expected.length, actual.length);

		double tolerance = tolerance(expected);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " gradient[" + i + "]", expected[i], actual[i], tolerance);
		}
	}

	/**
	 * Evaluates a producer after optimization, as the backward pass would.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated result
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return Process.optimized(producer).get().evaluate();
	}
}
