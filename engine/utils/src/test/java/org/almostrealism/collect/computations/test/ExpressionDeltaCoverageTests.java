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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Function;

/**
 * Verifies the expression-level derivatives of operations whose {@code delta} used to be
 * missing, so that taking a Jacobian through them compiles and produces the correct result:
 * the absolute value ({@link io.almostrealism.expression.Absolute}), the element-wise minimum
 * ({@link io.almostrealism.expression.Min}), the modulo ({@link io.almostrealism.expression.Mod}),
 * the floor ({@link io.almostrealism.expression.Floor}), the two-argument arctangent
 * ({@link io.almostrealism.expression.Atan2}) and the ternary conditional
 * ({@link io.almostrealism.expression.Conditional}).
 *
 * <p>Each analytic Jacobian is checked against central differences of the forward computation,
 * evaluated at points kept away from the non-differentiable locations (the kink of {@code abs},
 * ties in {@code min}, and the integer boundaries of {@code mod} and {@code floor}).</p>
 *
 * @author Michael Murray
 */
public class ExpressionDeltaCoverageTests extends TestSuiteBase {
	/** The perturbation step used when forming central differences of the forward computation. */
	private static final double STEP = 1e-3;

	/** The maximum difference allowed between an analytic derivative and its central difference. */
	private static final double TOLERANCE = 5e-3;

	/**
	 * Reads a one-dimensional collection into a {@code double} array.
	 *
	 * @param c the collection to read
	 * @return the element values of {@code c} in order
	 */
	private double[] toArray(PackedCollection c) {
		int n = c.getShape().getTotalSize();
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			out[i] = c.toDouble(i);
		}
		return out;
	}

	/**
	 * Builds the forward computation and its analytic Jacobian with respect to the input, then
	 * asserts the Jacobian agrees element-by-element with central differences of the forward pass.
	 *
	 * <p>Each column of the numerical Jacobian perturbs a single input element by {@code ±STEP}
	 * and re-evaluates the framework's own forward computation, so the finite differences reflect
	 * the compiled forward pass rather than a host-side model of it. The perturbation is formed in
	 * the computation graph as a one-hot vector (an {@code equals} against {@code integers}) scaled
	 * by the step and added to the base input, avoiding any host-side array rewrite.</p>
	 *
	 * @param name  a label used in assertion messages
	 * @param build constructs the forward computation over a given input producer
	 * @param input the point at which the derivative is evaluated
	 */
	private void assertDeltaMatchesCentralDifference(String name,
													 Function<CollectionProducer, CollectionProducer> build,
													 PackedCollection input) {
		int n = input.getShape().getTotalSize();
		CollectionProducer xp = cp(input);
		CollectionProducer forward = build.apply(xp);

		int out = forward.get().evaluate().getShape().getTotalSize();
		PackedCollection analytic = forward.delta(xp).get().evaluate().reshape(out, n);

		for (int j = 0; j < n; j++) {
			CollectionProducer oneHot = equals(integers(0, n), c((double) j));
			PackedCollection plus = cp(input).add(oneHot.multiply(STEP)).get().evaluate().reshape(n);
			PackedCollection minus = cp(input).add(oneHot.multiply(-STEP)).get().evaluate().reshape(n);
			double[] fPlus = toArray(build.apply(cp(plus)).get().evaluate());
			double[] fMinus = toArray(build.apply(cp(minus)).get().evaluate());

			for (int i = 0; i < out; i++) {
				double numeric = (fPlus[i] - fMinus[i]) / (2.0 * STEP);
				assertEquals(name + " d[" + i + "]/d[" + j + "]",
						numeric, analytic.valueAt(i, j), TOLERANCE);
			}
		}
	}

	/**
	 * Tests d/dx[|x - 1|], whose derivative is the sign of {@code x - 1}: the operand is a
	 * non-trivial function of the target (chain rule), and the evaluation points straddle the
	 * kink so each sign is exercised.
	 */
	@Test(timeout = 120000)
	public void absoluteDelta() {
		assertDeltaMatchesCentralDifference("abs(x - 1)",
				x -> abs(x.subtract(1.0)),
				pack(0.5, 1.5, 2.0, 3.0));
	}

	/**
	 * Tests d/dx[min(x, w)] where each operand is the smaller for some elements, so both branches
	 * of the derivative selection are taken. Where {@code x} wins the derivative is one; where
	 * {@code w} wins it is zero.
	 */
	@Test(timeout = 120000)
	public void minimumDelta() {
		PackedCollection w = pack(2.0, -1.0, 0.5, 4.0);
		assertDeltaMatchesCentralDifference("min(x, w)",
				x -> min(x, cp(w)),
				pack(0.5, 1.5, 2.0, 3.0));
	}

	/**
	 * Tests d/dx[x mod 1], the reproduction from the defect report: with a constant modulus the
	 * derivative is the derivative of the dividend, a diagonal of ones. Evaluation points are
	 * non-integers so the remainder does not step between the perturbed samples.
	 */
	@Test(timeout = 120000)
	public void moduloConstantModulusDelta() {
		assertDeltaMatchesCentralDifference("x mod 1",
				x -> x.mod(1.0),
				pack(0.3, 0.7, 1.1, 2.2));
	}

	/**
	 * Tests d/dx[7 mod x], exercising the rule for a modulus that depends on the target:
	 * d/dx (u mod m) includes the {@code -floor(u / m)} term when {@code m} varies. Evaluation
	 * points avoid the {@code x = 7 / k} boundaries where {@code 7 / x} crosses an integer.
	 */
	@Test(timeout = 120000)
	public void moduloTargetDependentModulusDelta() {
		PackedCollection dividend = pack(7.0, 7.0, 7.0, 7.0);
		assertDeltaMatchesCentralDifference("7 mod x",
				x -> mod(cp(dividend), x),
				pack(1.3, 2.3, 3.3, 5.3));
	}

	/**
	 * Tests d/dx[u mod x] for negative (and mixed-sign) dividends. The floating-point remainder
	 * truncates its quotient toward zero, so the modulus term of the derivative is
	 * {@code -trunc(u / x)}, which differs by one from {@code -floor(u / x)} whenever
	 * {@code u / x} is negative. Evaluation points avoid the boundaries where {@code u / x}
	 * crosses an integer.
	 */
	@Test(timeout = 120000)
	public void moduloNegativeDividendDelta() {
		PackedCollection dividend = pack(-7.0, -1.5, 4.2, -4.2);
		assertDeltaMatchesCentralDifference("u mod x (negative u)",
				x -> mod(cp(dividend), x),
				pack(1.3, 2.1, 2.3, 2.3));
	}

	/**
	 * Tests d/dx[floor(x)], which is zero everywhere it is differentiable. Evaluation points are
	 * non-integers so the floor does not step between the perturbed samples.
	 */
	@Test(timeout = 120000)
	public void floorDelta() {
		assertDeltaMatchesCentralDifference("floor(x)",
				x -> floor(x),
				pack(0.5, 1.5, 2.7, 3.2));
	}

	/**
	 * Tests d/dx[atan2(x, 2)] = 2 / (x^2 + 4), a smooth derivative kept away from the origin.
	 */
	@Test(timeout = 120000)
	public void atan2Delta() {
		assertDeltaMatchesCentralDifference("atan2(x, 2)",
				x -> atan2(x, c(2.0)),
				pack(-1.0, 0.5, 1.0, 2.0));
	}

	/**
	 * Tests d/dx of a ternary conditional {@code x > 1 ? 2x : 3x}, whose derivative selects the
	 * derivative of the taken branch (two or three). Evaluation points straddle the threshold so
	 * both branches are exercised, and none sits on the boundary.
	 */
	@Test(timeout = 120000)
	public void conditionalDelta() {
		PackedCollection threshold = pack(1.0, 1.0, 1.0, 1.0);
		assertDeltaMatchesCentralDifference("x > 1 ? 2x : 3x",
				x -> greaterThanConditional(x, cp(threshold), x.multiply(2.0), x.multiply(3.0)),
				pack(0.2, 0.7, 1.5, 2.5));
	}
}
