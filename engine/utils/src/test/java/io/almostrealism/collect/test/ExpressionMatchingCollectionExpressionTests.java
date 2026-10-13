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
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Verifies how an {@link ExpressionMatchingCollectionExpression} selects between its
 * operands: once for every index, when whether the reference matches the compared
 * expression is the same everywhere, and index by index otherwise, as in the derivative of
 * a reference with respect to the collection it is an element of. Also verifies when two
 * referenced variables are taken to be the same, which decides whether their references
 * match at all.
 */
public class ExpressionMatchingCollectionExpressionTests extends TestSuiteBase implements ExpressionFeatures {

	/** The shape of every collection compared in these tests. */
	private static final TraversalPolicy SHAPE = new TraversalPolicy(8);

	/**
	 * Returns a collection whose every element is the given integer constant.
	 *
	 * @param value the value of every element
	 * @return a new constant collection
	 */
	private CollectionExpression uniform(int value) {
		return new ConstantCollectionExpression(SHAPE, e(value));
	}

	/**
	 * When the reference matches the compared expression at every index, or at none, the
	 * operand selected everywhere is created itself, rather than a conditional.
	 */
	@Test(timeout = 30000)
	public void uniformMatchCreatesTheSelectedOperand() {
		CollectionExpression positive = uniform(1);
		CollectionExpression negative = uniform(0);

		Assert.assertSame(positive, ExpressionMatchingCollectionExpression.create(uniform(3), uniform(3), positive, negative));
		Assert.assertSame(negative, ExpressionMatchingCollectionExpression.create(uniform(3), uniform(4), positive, negative));
	}

	/**
	 * The derivative of a reference to one element of an array, with respect to the
	 * collection of the array's elements, is one at that element and zero at every other.
	 */
	@Test(timeout = 30000)
	public void referenceDeltaSelectsItsOwnElement() {
		ArrayVariable<Double> array = new ArrayVariable<>(Double.class, "array", e(8));
		CollectionExpression elements = CollectionExpression.create(SHAPE, i -> new InstanceReference<>(array, i, i));

		CollectionExpression delta = new InstanceReference<>(array, e(2), e(2)).delta(elements);

		Assert.assertEquals(OptionalInt.of(1), delta.getValueAt(e(2)).intValue());
		Assert.assertEquals(OptionalInt.of(0), delta.getValueAt(e(3)).intValue());
		Assert.assertFalse(delta.isIndexIndependent());
	}

	/**
	 * A matching expression whose selection is the same at every index is independent of
	 * the index exactly when the operand it selects is.
	 */
	@Test(timeout = 30000)
	public void indexIndependenceFollowsTheUniformSelection() {
		CollectionExpression fixed = uniform(1);
		CollectionExpression varying = CollectionExpression.create(SHAPE, i -> i);

		Assert.assertTrue(new ExpressionMatchingCollectionExpression(uniform(3), uniform(3), fixed, varying).isIndexIndependent());
		Assert.assertFalse(new ExpressionMatchingCollectionExpression(uniform(3), uniform(3), varying, fixed).isIndexIndependent());
		Assert.assertTrue(new ExpressionMatchingCollectionExpression(uniform(3), uniform(4), varying, fixed).isIndexIndependent());
		Assert.assertFalse(new ExpressionMatchingCollectionExpression(uniform(3), uniform(4), fixed, varying).isIndexIndependent());
	}

	/**
	 * Variables are the same when they share a name, and collection variables are also the
	 * same when they are produced by matching producers, so that a reference to an argument
	 * matches a reference to the target of a derivative under another name.
	 */
	@Test(timeout = 30000)
	public void collectionVariablesWithMatchingProducersAreTheSame() {
		CollectionVariable<?> input = new CollectionVariable("input", SHAPE, new PassThroughProducer<>(SHAPE, 0));
		CollectionVariable<?> target = new CollectionVariable("target", SHAPE, new PassThroughProducer<>(SHAPE, 0));
		CollectionVariable<?> other = new CollectionVariable("other", SHAPE, new PassThroughProducer<>(SHAPE, 1));

		Assert.assertTrue(new Variable<>("input").isSameAs(new Variable<>("input")));
		Assert.assertFalse(new Variable<>("input").isSameAs(new Variable<>("target")));
		Assert.assertTrue(input.isSameAs(target));
		Assert.assertFalse(input.isSameAs(other));

		Assert.assertEquals(Optional.of(true), new InstanceReference<>(input, e(2), e(2))
				.matches(new InstanceReference<>(target, e(2), e(2))).booleanValue());
		Assert.assertEquals(Optional.of(false), new InstanceReference<>(input, e(2), e(2))
				.matches(new InstanceReference<>(other, e(2), e(2))).booleanValue());
	}
}
