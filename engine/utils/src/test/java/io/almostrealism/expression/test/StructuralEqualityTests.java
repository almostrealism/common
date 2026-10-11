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
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

/**
 * Verifies {@link Expression#structuralEq(Expression)}, its {@link InstanceReference}
 * override, {@link Variable#refersToSameValue(Variable)}, and
 * {@link ArrayVariable#getTraversableExpression()}.
 */
public class StructuralEqualityTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Returns the statically known value of a boolean expression, failing if it has none.
	 *
	 * @param e the boolean expression
	 * @return its constant value
	 */
	private static boolean known(Expression<Boolean> e) {
		Optional<Boolean> value = e.booleanValue();
		Assert.assertTrue("Expected a constant result but got " + e, value.isPresent());
		return value.get();
	}

	/**
	 * Two scalar references carry no index; comparing them must not dereference the
	 * missing index, and must report a match exactly when the referents are the same.
	 */
	@Test(timeout = 30000)
	public void scalarReferences() {
		Variable<Double, ?> x = new Variable<>("x", Double.class);
		Variable<Double, ?> xAgain = new Variable<>("x", Double.class);
		Variable<Double, ?> y = new Variable<>("y", Double.class);

		Assert.assertTrue(known(new InstanceReference<>(x).structuralEq(new InstanceReference<>(xAgain))));
		Assert.assertFalse(known(new InstanceReference<>(x).structuralEq(new InstanceReference<>(y))));
	}

	/**
	 * A reference with an index never matches one without, in either direction.
	 */
	@Test(timeout = 30000)
	public void indexedAndScalarReferencesDiffer() {
		Variable<Double, ?> x = new Variable<>("x", Double.class);
		InstanceReference<Double, ?> scalar = new InstanceReference<>(x);
		InstanceReference<Double, ?> indexed = new InstanceReference<>(x, e(2), e(2));

		Assert.assertFalse(known(scalar.structuralEq(indexed)));
		Assert.assertFalse(known(indexed.structuralEq(scalar)));
	}

	/**
	 * Indexed references to the same referent match where their indices are equal; a
	 * reference compared with an expression that is not a reference never matches.
	 */
	@Test(timeout = 30000)
	public void indexedReferences() {
		Variable<Double, ?> x = new Variable<>("x", Double.class);
		InstanceReference<Double, ?> atTwo = new InstanceReference<>(x, e(2), e(2));

		Assert.assertTrue(known(atTwo.structuralEq(new InstanceReference<>(x, e(2), e(2)))));
		Assert.assertFalse(known(atTwo.structuralEq(new InstanceReference<>(x, e(3), e(3)))));
		Assert.assertFalse(known(atTwo.structuralEq(e(2))));
	}

	/**
	 * For ordinary expressions the comparison is structural: equal trees match, while
	 * trees of different classes or arity are statically different.
	 */
	@Test(timeout = 30000)
	public void compositeExpressions() {
		Expression<?> sum = kernel().add(e(1));

		Assert.assertTrue(known(sum.structuralEq(kernel().add(e(1)))));
		Assert.assertFalse(known(sum.structuralEq(kernel().multiply(e(3)))));
		Assert.assertFalse(known(sum.structuralEq(kernel())));
		Assert.assertTrue(known(e(4).structuralEq(e(4))));
		Assert.assertFalse(known(e(4).structuralEq(e(5))));
	}

	/**
	 * Variables stand for the same value by name by default.
	 */
	@Test(timeout = 30000)
	public void refersToSameValueByName() {
		Variable<Double, ?> x = new Variable<>("x", Double.class);

		Assert.assertTrue(x.refersToSameValue(new Variable<>("x", Double.class)));
		Assert.assertFalse(x.refersToSameValue(new Variable<>("y", Double.class)));
	}

	/**
	 * A plain array variable is not traversable, but one that delegates to a collection
	 * variable answers with that collection variable, through any length of chain.
	 */
	@Test(timeout = 30000)
	public void traversableExpressionFollowsDelegates() {
		ArrayVariable<Double> plain = new ArrayVariable<>(Double.class, "plain", e(4));
		Assert.assertNull(plain.getTraversableExpression());

		CollectionVariable<?> collection = new CollectionVariable<>("c", new TraversalPolicy(4), null);
		Assert.assertSame(collection, collection.getTraversableExpression());

		ArrayVariable<?> view = new ArrayVariable(collection, e(1));
		Assert.assertSame(collection, view.getTraversableExpression());

		ArrayVariable<?> nested = new ArrayVariable(view, e(1));
		Assert.assertSame(collection, nested.getTraversableExpression());

		Assert.assertNull(new ArrayVariable<>(plain, e(1)).getTraversableExpression());
	}
}
