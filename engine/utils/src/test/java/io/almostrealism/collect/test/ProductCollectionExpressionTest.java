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

package io.almostrealism.collect.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.DeltaCollectionExpression;
import io.almostrealism.collect.DiagonalCollectionExpression;
import io.almostrealism.collect.ProductCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the diagonal shortcut of {@link ProductCollectionExpression#delta(CollectionExpression)}
 * and that every case it does not cover falls back to the inherited delta.
 */
public class ProductCollectionExpressionTest extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * The product of an opaque input with an operand that does not reference it
	 * differentiates to a diagonal.
	 */
	@Test(timeout = 30000)
	public void productWithOtherOperandIsDiagonal() {
		TraversalPolicy shape = new TraversalPolicy(4);
		CollectionVariable<?> x = new CollectionVariable<>("x", shape, null);
		ProductCollectionExpression product = new ProductCollectionExpression(shape,
				x, new ConstantCollectionExpression(shape, e(3.0)));

		Assert.assertTrue(product.delta(x) instanceof DiagonalCollectionExpression);
	}

	/**
	 * A product whose only operand is the target has no other operand to form the
	 * diagonal from, so it must answer with the inherited delta rather than fail.
	 */
	@Test(timeout = 30000)
	public void productOfTargetAloneFallsBackToInheritedDelta() {
		TraversalPolicy shape = new TraversalPolicy(4);
		CollectionVariable<?> x = new CollectionVariable<>("x", shape, null);
		ProductCollectionExpression product = new ProductCollectionExpression(shape, x);

		Assert.assertTrue(product.delta(x) instanceof DeltaCollectionExpression);
	}
}
