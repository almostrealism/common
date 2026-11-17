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

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the type of a {@link Conditional} whose branches have different types, and that
 * an integer conditional stays integer when one of its branches is replaced by a reference
 * to a declared common subexpression, as {@link io.almostrealism.scope.Scope} does when it
 * simplifies a kernel.
 */
public class ConditionalTypeTest extends TestSuiteBase {

	/** Selecting between an {@link Integer} and a {@link Long} gives a {@link Long}, not a floating-point value. */
	@Test(timeout = 30000)
	public void integerAndLongBranchesAreLong() {
		Expression<Boolean> condition = new KernelIndex().withLimit(8).greaterThan(new IntegerConstant(2));
		Expression<?> selected = Conditional.of(condition,
				new IntegerConstant(3), new StaticReference<>(Long.class, "ref"));

		Assert.assertEquals(Long.class, selected.getType());
		Assert.assertFalse(selected.isFP());
	}

	/** A floating-point branch still makes the conditional floating-point. */
	@Test(timeout = 30000)
	public void floatingPointBranchIsDouble() {
		Expression<Boolean> condition = new KernelIndex().withLimit(8).greaterThan(new IntegerConstant(2));
		Expression<?> selected = Conditional.of(condition,
				new IntegerConstant(3), new DoubleConstant(0.5));

		Assert.assertEquals(Double.class, selected.getType());
		Assert.assertTrue(selected.isFP());
	}

	/**
	 * A {@link Boolean} branch has no numeric promotion, so mixing it with an integer branch
	 * gives a floating-point conditional rather than failing to construct one.
	 */
	@Test(timeout = 30000)
	public void booleanAndIntegerBranchesAreDouble() {
		KernelIndex index = new KernelIndex().withLimit(8);
		Expression<Boolean> condition = index.greaterThan(new IntegerConstant(2));
		Expression<?> selected = Conditional.of(condition,
				index.greaterThan(new IntegerConstant(5)), new IntegerConstant(3));

		Assert.assertEquals(Double.class, selected.getType());
		Assert.assertTrue(selected.isFP());

		Expression<?> reversed = Conditional.of(condition,
				new IntegerConstant(3), index.greaterThan(new IntegerConstant(5)));
		Assert.assertEquals(Double.class, reversed.getType());
	}

	/** Two {@link Boolean} branches keep their common type. */
	@Test(timeout = 30000)
	public void booleanBranchesAreBoolean() {
		KernelIndex index = new KernelIndex().withLimit(8);
		Expression<?> selected = Conditional.of(index.greaterThan(new IntegerConstant(2)),
				index.greaterThan(new IntegerConstant(5)), index.greaterThan(new IntegerConstant(1)));

		Assert.assertEquals(Boolean.class, selected.getType());
	}

	/** Two {@link Integer} branches stay {@link Integer}. */
	@Test(timeout = 30000)
	public void integerBranchesAreInteger() {
		Expression<Boolean> condition = new KernelIndex().withLimit(8).greaterThan(new IntegerConstant(2));
		Expression<?> selected = Conditional.of(condition,
				new IntegerConstant(3), new IntegerConstant(7));

		Assert.assertEquals(Integer.class, selected.getType());
		Assert.assertFalse(selected.isFP());
	}

	/**
	 * An integer modulus of a nested integer conditional, such as the wrapped index of a
	 * maximum search, survives replacing the inner conditional with a reference of its
	 * declaration type. That type is {@link Long} for anything that depends on the kernel
	 * index, so the outer conditional then selects between an {@link Integer} and a
	 * {@link Long}; it must remain an integer for the integer modulus to accept it.
	 */
	@Test(timeout = 30000)
	public void integerModulusSurvivesBranchReplacement() {
		KernelIndex index = new KernelIndex().withLimit(8);
		Expression<?> inner = Conditional.of(index.greaterThan(new IntegerConstant(1)),
				new IntegerConstant(2), new IntegerConstant(1));
		Expression<?> outer = Conditional.of(index.greaterThan(new IntegerConstant(4)),
				new IntegerConstant(4), inner);
		Expression<?> wrapped = outer.imod(3);

		Assert.assertEquals(Integer.class, inner.getType());
		Assert.assertEquals(Long.class, inner.getDeclarationType());
		Assert.assertFalse(wrapped.isFP());

		Expression<?> replaced = wrapped.replace(inner,
				new StaticReference<>(inner.getDeclarationType(), "ref"));
		Assert.assertFalse(replaced.isFP());
		Assert.assertTrue(replaced.getExpression(Expression.defaultLanguage()).contains("ref"));
	}
}
