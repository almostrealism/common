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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.ScalarMatrixComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Function;

/**
 * Reproduces and pins the diagonal placement of {@link ScalarMatrixComputation}.
 *
 * <p>A {@link ScalarMatrixComputation} over an {@code (n, n)} shape must carry the scalar on
 * every diagonal entry, regardless of the traversal axis its shape happens to carry. The
 * computation reached by {@link org.almostrealism.collect.ArithmeticFeatures#minus(io.almostrealism.relation.Producer)}
 * whenever its operand is an identity matrix is exactly this computation, so the Jacobian of
 * {@code -x} for any one-dimensional {@code x} flows through it. These tests assert the forward
 * scalar matrix and the negation Jacobians against both the hand-written values and central
 * differences.</p>
 *
 * @author Michael Murray
 */
public class ScalarMatrixComputationTests extends TestSuiteBase {

	/**
	 * The perturbation step of the central differences. On a single-precision backend the
	 * rounding error of a central difference is about {@code ulp(f) / (2 * step)}, which for the
	 * inputs here exceeds the {@code 1e-3} relative tolerance once the step is as small as
	 * {@code 1e-4}. At this step the estimate is exact for the linear negations and its truncation
	 * error, {@code step^2 f''' / 6}, stays near {@code 1e-5} for the sigmoid and exponential.
	 */
	private static final double DIFFERENCE_STEP = 1e-2;

	/**
	 * The scalar matrix must put the scalar on every diagonal entry whether its shape traverses
	 * axis 0 (the default for {@code shape(n, n)}) or axis 1.
	 */
	@Test(timeout = 120000)
	public void scalarMatrixDiagonalForEveryTraversalAxis() {
		int n = 4;

		PackedCollection axisZero =
				new ScalarMatrixComputation(shape(n, n), c(-1.0)).evaluate().reshape(n, n);
		PackedCollection axisOne =
				new ScalarMatrixComputation(shape(n, n).traverse(1), c(-1.0)).evaluate().reshape(n, n);

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expected = (i == j) ? -1.0 : 0.0;
				assertEquals("scalarMatrix(traverse 0)[" + i + ", " + j + "]",
						expected, axisZero.valueAt(i, j));
				assertEquals("scalarMatrix(traverse 1)[" + i + ", " + j + "]",
						expected, axisOne.valueAt(i, j));
			}
		}
	}

	/**
	 * {@code minus(identity(n))} takes the identity shortcut and must still be {@code -I}, matching
	 * the plain {@code identity(n)} it negates.
	 */
	@Test(timeout = 120000)
	public void minusOfIdentityIsNegatedIdentity() {
		int n = 4;

		PackedCollection identity = identity(n).evaluate().reshape(n, n);
		PackedCollection negated = minus(identity(n)).evaluate().reshape(n, n);

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expectedIdentity = (i == j) ? 1.0 : 0.0;
				assertEquals("identity[" + i + ", " + j + "]", expectedIdentity, identity.valueAt(i, j));
				assertEquals("-identity[" + i + ", " + j + "]", -expectedIdentity, negated.valueAt(i, j));
			}
		}
	}

	/**
	 * The Jacobian of a direct negation of a one-dimensional input is {@code -I}. Every path that
	 * negates the differentiation target directly — {@code minus()}, {@code multiply(-1)},
	 * {@code 0 - x}, and {@code w - x} — routes through the identity shortcut and so must produce
	 * {@code -I}, agreeing with central differences.
	 */
	@Test(timeout = 120000)
	public void negationJacobianIsNegatedIdentity() {
		assertNegatedIdentityJacobian("minus", x -> x.minus());
		assertNegatedIdentityJacobian("multiply(-1)", x -> x.multiply(-1.0));
		assertNegatedIdentityJacobian("0 - x", x -> c(0.0).subtract(x));
		assertNegatedIdentityJacobian("w - x", x -> cp(pack(2.0, -1.0, 0.5, 4.0)).subtract(x));
	}

	/**
	 * The Jacobian of {@code sigmoid(x)} is diagonal, carrying {@code s(x)(1 - s(x))}; the Jacobian
	 * of {@code sigmoid(-x)} is diagonal, carrying {@code -s(-x)(1 - s(-x))}; and the Jacobian of
	 * {@code exp(-x)} is diagonal carrying {@code -exp(-x)}. The negated cases compose the
	 * {@link ScalarMatrixComputation} produced by the identity shortcut of {@code minus()} with an
	 * element-wise cofactor, so a corner-only scalar matrix would leave every entry but
	 * {@code [0, 0]} zero. The un-negated sigmoid is kept as the reference the negated one is
	 * checked against.
	 */
	@Test(timeout = 120000)
	public void negationComposedWithUnaryHasFullDiagonalJacobian() {
		int n = 4;
		PackedCollection x = scalarInput();

		CollectionProducer sigmoid = cp(x).sigmoid();
		Evaluable<PackedCollection> sigmoidForward = sigmoid.get();
		double[] sigmoidAnalytic = sigmoid.delta(cp(x)).evaluate().toArray();
		double[] sigmoidNumeric = numericJacobian(x, n, sigmoidForward);
		assertSigmoidJacobian("d sigmoid", n, 1.0, sigmoidForward, sigmoidAnalytic);
		assertGradientMatches("d sigmoid vs central differences", sigmoidNumeric, sigmoidAnalytic,
				1e-3, 1e-3);

		CollectionProducer sigmoidMinus = cp(x).minus().sigmoid();
		Evaluable<PackedCollection> sigmoidMinusForward = sigmoidMinus.get();
		double[] sigmoidMinusAnalytic = sigmoidMinus.delta(cp(x)).evaluate().toArray();
		double[] sigmoidMinusNumeric = numericJacobian(x, n, sigmoidMinusForward);
		assertSigmoidJacobian("d sigmoid(-x)", n, -1.0, sigmoidMinusForward, sigmoidMinusAnalytic);
		assertGradientMatches("d sigmoid(-x) vs central differences", sigmoidMinusNumeric,
				sigmoidMinusAnalytic, 1e-3, 1e-3);

		CollectionProducer expMinus = cp(x).minus().exp();
		Evaluable<PackedCollection> expForward = expMinus.get();
		double[] expAnalytic = expMinus.delta(cp(x)).evaluate().toArray();
		double[] expNumeric = numericJacobian(x, n, expForward);
		for (int i = 0; i < n; i++) {
			double expected = -Math.exp(-x.toDouble(i));
			assertEquals("d exp(-x)[" + i + ", " + i + "]", expected, expAnalytic[i * n + i], 1e-4);
		}
		assertGradientMatches("d exp(-x) vs central differences", expNumeric, expAnalytic,
				1e-3, 1e-3);
	}

	/**
	 * {@code sum(-x * w)} has gradient {@code -w} with respect to {@code x}; the corner-only identity
	 * bug collapses this to {@code [-w[0], 0, 0, 0]}.
	 */
	@Test(timeout = 120000)
	public void negatedWeightedSumGradientIsNegatedWeights() {
		int n = 4;
		PackedCollection x = scalarInput();
		PackedCollection w = pack(2.0, -1.0, 0.5, 4.0);

		CollectionProducer loss = cp(x).minus().multiply(cp(w)).sum();
		double[] analytic = loss.delta(cp(x)).evaluate().toArray();

		for (int i = 0; i < n; i++) {
			assertEquals("d sum(-x*w) / d x[" + i + "]", -w.toDouble(i), analytic[i], 1e-5);
		}

		Evaluable<PackedCollection> lossForward = loss.get();
		double[] numeric = centralDifferences(x, DIFFERENCE_STEP, () -> lossForward.evaluate().toDouble(0));
		assertGradientMatches("d sum(-x*w) vs central differences", numeric, analytic, 1e-3, 1e-3);
	}

	/**
	 * The shared one-dimensional input used by the negation tests.
	 *
	 * @return a fresh four-element input vector
	 */
	private PackedCollection scalarInput() {
		return pack(0.5, 1.5, 2.0, 3.0);
	}

	/**
	 * Asserts that the Jacobian of {@code negation} applied to a fresh {@link #scalarInput()} is
	 * {@code -I}, both against the hand-written entries and against central differences. The
	 * negation is built from a producer of the very input that is then differentiated against, so
	 * the derivative is with respect to the operand being negated.
	 *
	 * @param label   a description of the negation, for the messages
	 * @param negation builds the negation from a producer of the one-dimensional input
	 */
	private void assertNegatedIdentityJacobian(String label,
											   Function<CollectionProducer, CollectionProducer> negation) {
		PackedCollection x = scalarInput();
		int n = x.getMemLength();

		double[] analytic = negation.apply(cp(x)).delta(cp(x)).evaluate().toArray();
		assertEquals(label + ": Jacobian size", n * n, analytic.length);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expected = (i == j) ? -1.0 : 0.0;
				assertEquals(label + " Jacobian[" + i + ", " + j + "]", expected, analytic[i * n + j], 1e-6);
			}
		}

		double[] numeric = numericJacobian(x, n, negation.apply(cp(x)).get());
		assertGradientMatches(label + " vs central differences", numeric, analytic, 1e-3, 1e-3);
	}

	/**
	 * Asserts that {@code analytic} is the diagonal Jacobian of {@code sigmoid(sign * x)}: the
	 * diagonal carries {@code sign * s(1 - s)}, where {@code s} is the forward output, and every
	 * off-diagonal entry is zero.
	 *
	 * @param label    a description of the Jacobian, for the messages
	 * @param n        the dimension of the square Jacobian
	 * @param sign     the factor the sigmoid's argument applies to the input ({@code 1} or {@code -1})
	 * @param forward  the forward sigmoid, read for its output on the diagonal
	 * @param analytic the Jacobian to check, in row-major order
	 */
	private void assertSigmoidJacobian(String label, int n, double sign,
									   Evaluable<PackedCollection> forward, double[] analytic) {
		PackedCollection f = forward.evaluate();
		for (int i = 0; i < n; i++) {
			double sigma = f.toDouble(i);
			double expected = sign * sigma * (1.0 - sigma);
			assertEquals(label + "[" + i + ", " + i + "]", expected, analytic[i * n + i], 1e-4);
			for (int j = 0; j < n; j++) {
				if (i != j) {
					assertEquals(label + "[" + i + ", " + j + "]", 0.0, analytic[i * n + j], 1e-6);
				}
			}
		}
	}

	/**
	 * Builds the finite-difference Jacobian of {@code forward} with respect to {@code x} by taking
	 * central differences of each output component against every input element.
	 *
	 * @param x       the input to perturb, read by {@code forward}
	 * @param outputs the number of output components
	 * @param forward the forward pass evaluated at the current contents of {@code x}
	 * @return the numeric Jacobian in row-major (output, input) order
	 */
	private double[] numericJacobian(PackedCollection x, int outputs,
									 Evaluable<PackedCollection> forward) {
		int inputs = x.getMemLength();
		double[] jacobian = new double[outputs * inputs];
		for (int i = 0; i < outputs; i++) {
			int row = i;
			double[] column = centralDifferences(x, DIFFERENCE_STEP, () -> forward.evaluate().toDouble(row));
			for (int j = 0; j < inputs; j++) {
				jacobian[i * inputs + j] = column[j];
			}
		}
		return jacobian;
	}
}
