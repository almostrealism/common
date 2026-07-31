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

package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mod;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that floating-point modulo expressions carry a floating-point type
 * regardless of the dividend type.
 *
 * <p>A floating-point {@link Mod} renders as {@code fmod}, which produces a
 * floating-point value. When such an expression was typed after an integer
 * dividend, consumers saw an integer-typed expression that rendered as a
 * floating-point value: {@code toInt()} skipped its cast, and an enclosing
 * integer modulo or array subscript then generated code the native compiler
 * rejected ({@code invalid operands to binary expression ('double' and 'int')}).
 * The per-channel gather in the learnable Snake activation produced exactly
 * this pattern through its index expression.</p>
 */
public class FloatingPointModTypeTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Verifies that a floating-point constant reports its value through the
	 * integer value accessors whenever the integer type represents it without
	 * loss. These are value accessors, not type conversions, so integral
	 * doubles must report and fractional doubles must not.
	 */
	@Test(timeout = 30000)
	public void doubleConstantIntegerValues() {
		Assert.assertEquals(128, new DoubleConstant(128.0).intValue().getAsInt());
		Assert.assertEquals(128L, new DoubleConstant(128.0).longValue().getAsLong());
		Assert.assertEquals(100000000000L, new DoubleConstant(100000000000.0).longValue().getAsLong());

		Assert.assertTrue("A value beyond the 32-bit range must not report as an int",
				new DoubleConstant(100000000000.0).intValue().isEmpty());
		Assert.assertTrue("A fractional value must not report as an int",
				new DoubleConstant(128.5).intValue().isEmpty());
		Assert.assertTrue("A fractional value must not report as a long",
				new DoubleConstant(128.5).longValue().isEmpty());
	}

	/**
	 * Verifies that a modulo of an integer-typed dividend by a floating-point
	 * constant that an integer represents without loss is converted to an
	 * integer modulo, so the generated code contains no {@code fmod}.
	 */
	@Test(timeout = 30000)
	public void integralConstantDivisorProducesIntegerMod() {
		Expression index = new KernelIndex();
		Assert.assertFalse(index.isFP());

		Expression mod = Mod.of(index, new DoubleConstant(128.0), true);
		Assert.assertFalse("An integer value modulo an integral constant must be integer typed",
				mod.isFP());

		String rendered = mod.getExpression(new LanguageOperationsStub());
		Assert.assertFalse("An integer value modulo an integral constant must not render fmod: " + rendered,
				rendered.contains("fmod"));
	}

	/**
	 * Verifies that a genuinely floating-point modulo over an integer-typed
	 * dividend is floating-point typed, so integer consumers apply the casts
	 * they need.
	 */
	@Test(timeout = 30000)
	public void fpModOfIntegerDividendIsFloatingPoint() {
		Expression index = new KernelIndex();
		Assert.assertFalse(index.isFP());

		Expression mod = Mod.of(index, new DoubleConstant(128.5), true);
		Assert.assertTrue("A floating-point modulo must be floating-point typed",
				mod.isFP());

		Expression cast = mod.toInt();
		Assert.assertFalse("toInt() of a floating-point modulo must produce an integer expression",
				cast.isFP());
		Assert.assertNotEquals("toInt() of a floating-point modulo must not be an identity",
				mod, cast);
	}

	/**
	 * Verifies that a gather whose index chain passes through floating-point
	 * arithmetic compiles and produces correct values. This is the construct
	 * used by the learnable Snake activation to expand per-channel parameters.
	 */
	@Test(timeout = 120000)
	public void gatherWithFloatingPointIndexChain() {
		int channels = 4;
		int seqLen = 8;
		int total = channels * seqLen;

		PackedCollection values = new PackedCollection(shape(channels));
		values.setMem(0, 10.0);
		values.setMem(1, 20.0);
		values.setMem(2, 30.0);
		values.setMem(3, 40.0);

		PackedCollection expanded = new PackedCollection(shape(channels, seqLen));

		CollectionProducer channelIndex = mod(
				floor(integers(0, total).divide(c((double) seqLen))),
				c((double) channels));
		a(cp(expanded), c(expanded.getShape(), cp(values), channelIndex)).get().run();

		for (int i = 0; i < total; i++) {
			assertEquals((i / seqLen + 1) * 10.0, expanded.toDouble(i));
		}
	}
}
