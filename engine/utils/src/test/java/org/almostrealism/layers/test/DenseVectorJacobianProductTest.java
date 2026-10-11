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
import org.almostrealism.algebra.MatrixMultiplyVectorJacobianProduct;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the structure-aware vector-Jacobian product of a dense layer's operator
 * produces exactly the gradient that contracting the operator's full Jacobian produces.
 *
 * <p>Every comparison is made on the whole dense operator built by
 * {@link LayerFeatures#denseOperator} (output reshape of the optional bias addition of the
 * matrix multiply), so a product that fails to survive those wrappers is caught. The side
 * under test is {@link #combineGradient}, which the backward pass uses; the full-Jacobian
 * reference is {@link #jacobianGradient}, which materializes the Jacobian regardless of
 * whether a shortcut exists, and for dense operators both are also compared with the
 * gradient computed on the host from its definition. Inputs are sequences of rows, so the
 * weight gradient must reduce the per-row outer products over every position.</p>
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
	 * of the vector-Jacobian product with the definition is asserted for the weight.</p>
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
	 * With a bias, the weight and input products are reached through the bias addition
	 * and agree with the full Jacobian; the bias itself is broadcast over the rows and
	 * keeps the full Jacobian.
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
		assertGradientsMatch(out, p(biases), cp(gradient), false);
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
		assertDenseGradientsMatch(shape(1, 5, 3), 1);
		assertDenseGradientsMatch(shape(3), 1);
	}

	/**
	 * An output wider than 1000 lowers the matrix multiply to a weighted sum rather than a
	 * multiply and sum; its product must agree with the full Jacobian too.
	 */
	@Test(timeout = 10 * 60000)
	public void wideOutputWeightedSum() {
		assertDenseGradientsMatch(shape(1, 3, 8), 1200);
	}

	/**
	 * Operators without a matrix multiply at the top offer no product, and their gradient
	 * is exactly the full-Jacobian gradient.
	 */
	@Test(timeout = 5 * 60000)
	public void nonMatmulFallsBack() {
		PackedCollection weights = new PackedCollection(shape(8, 6)).randnFill();
		PackedCollection input = new PackedCollection(shape(8, 6)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(8, 6)).randnFill();

		Producer<PackedCollection> in = cp(input);
		CollectionProducer elementwise = cp(input).multiply(p(weights));
		assertGradientsMatch(elementwise, p(weights), cp(gradient), false);
		assertGradientsMatch(elementwise, in, cp(gradient), false);

		CollectionProducer activated = sigmoid(matmul(p(weights), cp(input).traverse(1)));
		assertGradientsMatch(activated, p(weights), cp(new PackedCollection(shape(8, 8)).randnFill()), false);
	}

	/**
	 * A genuine matrix-by-matrix product has no row structure, so it offers no product and
	 * keeps the full Jacobian.
	 */
	@Test(timeout = 5 * 60000)
	public void matrixMatrixFallsBack() {
		PackedCollection a = new PackedCollection(shape(5, 4)).randnFill();
		PackedCollection b = new PackedCollection(shape(4, 3)).randnFill();
		CollectionProducer product = matmul(p(a), traverse(0, p(b)));
		Assert.assertEquals(15, product.getShape().getTotalSize());
		assertGradientsMatch(product, p(a), cp(new PackedCollection(shape(5, 3)).randnFill()), false);
	}

	/**
	 * When the rows are computed from the target rather than being the target, the product
	 * cannot express the chain through that computation, so none is offered and the
	 * gradient with respect to the target is the full-Jacobian gradient. The weight, which
	 * the rows cannot depend on, still has its product.
	 */
	@Test(timeout = 5 * 60000)
	public void targetFeedingRowsFallsBack() {
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
	 * operands, which neither half of the product accounts for, so none is offered and the
	 * gradient agrees with its definition {@code dW[a][b] = Σ_r g[r][a] W[r][b] + Σ_i g[a][i] W[i][b]}.
	 */
	@Test(timeout = 5 * 60000)
	public void matrixInBothOperandsFallsBack() {
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
	 * The sum of two dense operators sharing a weight matrix offers a product for that
	 * matrix which adds the products of both terms, {@code dW = Σ_r g_r ⊗ x_r + Σ_r g_r ⊗ z_r},
	 * and for each input a product from the one term it appears in.
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
	 * A sum offers no product when an operand that depends on the target is broadcast into
	 * it (its size differs from the sum's), even though the other operand alone offers one,
	 * nor when no operand depends on the target.
	 */
	@Test(timeout = 5 * 60000)
	public void sumDeclinesBroadcastAndIndependentOperands() {
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
		Assert.assertNull(broadcast.attemptVectorJacobianProduct(p(weights), cp(gradient)));
		Assert.assertNotNull(dense.attemptVectorJacobianProduct(p(weights), cp(gradient)));

		CollectionProducer independent = cp(input).add(cp(input));
		Assert.assertNull(independent.attemptVectorJacobianProduct(p(weights), cp(new PackedCollection(inputShape))));
	}

	/**
	 * The product declines, rather than computing a wrong gradient, whenever its operands
	 * or the upstream gradient do not have the row structure it assumes, or the target is
	 * not one of its operands.
	 */
	@Test(timeout = 60000)
	public void productDeclinesUnstructuredArguments() {
		Producer<PackedCollection> matrix = p(new PackedCollection(shape(3, 4)));
		Producer<PackedCollection> rows = cp(new PackedCollection(shape(5, 4)));
		Producer<PackedCollection> gradient = cp(new PackedCollection(shape(5, 3)));
		MatrixMultiplyVectorJacobianProduct product = new MatrixMultiplyVectorJacobianProduct(matrix, rows);

		Assert.assertEquals(12, product.apply(matrix, gradient).getShape().getTotalSize());
		Assert.assertEquals(20, product.apply(rows, gradient).getShape().getTotalSize());

		Assert.assertNull(product.apply(matrix, cp(new PackedCollection(shape(5, 4)))));
		Assert.assertNull(product.apply(rows, cp(new PackedCollection(shape(14)))));
		Assert.assertNull(product.apply(cp(new PackedCollection(shape(3, 4))), gradient));
		Assert.assertNull(product.apply(cp(new PackedCollection(shape(5, 4))), gradient));

		Assert.assertNull(new MatrixMultiplyVectorJacobianProduct(matrix, cp(new PackedCollection(shape(5, 3))))
				.apply(matrix, cp(new PackedCollection(shape(15)))));
		Producer<PackedCollection> cube = p(new PackedCollection(shape(2, 3, 4)));
		Assert.assertNull(new MatrixMultiplyVectorJacobianProduct(cube, rows)
				.apply(cube, cp(new PackedCollection(shape(30)))));
	}

	/**
	 * Builds a dense operator without bias for the given input shape and output width,
	 * and checks the weight and input gradients against their definition and against
	 * the full Jacobian.
	 *
	 * @param inputShape the input shape, whose last dimension is the input width
	 * @param nodes      the output width
	 */
	private void assertDenseGradientsMatch(TraversalPolicy inputShape, int nodes) {
		assertDenseGradientsMatch(inputShape, nodes, true);
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
	 */
	private void assertDenseGradientsMatch(TraversalPolicy inputShape, int nodes, boolean weightJacobianReliable) {
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

		assertGradientsMatch(out, p(weights), cp(gradient), true, weightGradient, weightJacobianReliable);
		assertGradientsMatch(out, in, cp(gradient), true, inputGradient, true);
	}

	/**
	 * Asserts that the gradient the backward pass uses equals the full-Jacobian gradient,
	 * and that a structure-aware product was or was not used, as expected.
	 *
	 * @param function        the forward operator
	 * @param target          the producer to differentiate with respect to
	 * @param gradient        the upstream gradient
	 * @param expectShortcut  whether the operator should offer a vector-Jacobian product
	 */
	private void assertGradientsMatch(CollectionProducer function, Producer<PackedCollection> target,
									  Producer<PackedCollection> gradient, boolean expectShortcut) {
		assertGradientsMatch(function, target, gradient, expectShortcut, null, true);
	}

	/**
	 * Asserts that a structure-aware product was or was not used, as expected, that the
	 * gradient the backward pass uses agrees with an independently computed reference when
	 * one is given, and that it agrees with the full-Jacobian gradient. When the
	 * full-Jacobian gradient is not reliable, its disagreement with the reference is
	 * logged instead of asserted.
	 *
	 * @param function         the forward operator
	 * @param target           the producer to differentiate with respect to
	 * @param gradient         the upstream gradient
	 * @param expectShortcut   whether the operator should offer a vector-Jacobian product
	 * @param reference        the gradient computed directly from its definition, or null
	 * @param jacobianReliable whether the full-Jacobian gradient is required to agree
	 */
	private void assertGradientsMatch(CollectionProducer function, Producer<PackedCollection> target,
									  Producer<PackedCollection> gradient, boolean expectShortcut,
									  double[] reference, boolean jacobianReliable) {
		Assert.assertEquals(expectShortcut,
				function.attemptVectorJacobianProduct(target, gradient) != null);

		PackedCollection actual = evaluate(combineGradient(function, target, gradient));
		PackedCollection expected = evaluate(jacobianGradient(function, target, gradient));

		int size = shape(target).getTotalSize();
		Assert.assertEquals(size, actual.getShape().getTotalSize());
		Assert.assertEquals(size, expected.getShape().getTotalSize());

		double[] a = actual.toArray(0, size);
		double[] e = expected.toArray(0, size);
		actual.destroy();
		expected.destroy();

		if (reference != null) {
			assertValuesMatch("vjp", reference, a);
		}

		if (jacobianReliable) {
			if (reference != null) assertValuesMatch("jacobian", reference, e);
			assertValuesMatch("vjp against jacobian", e, a);
		} else {
			int disagreements = 0;
			for (int i = 0; i < size; i++) {
				if (Math.abs(e[i] - reference[i]) > tolerance(reference)) disagreements++;
			}

			log("jacobianDisagreements=" + disagreements + " of " + size);
		}
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
