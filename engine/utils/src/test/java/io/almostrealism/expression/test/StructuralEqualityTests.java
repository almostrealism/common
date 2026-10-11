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
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;
import java.util.function.Supplier;

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
	 * References that carry a position but no supplementary index are distinguished by
	 * their positions, so references to different elements of the same referent differ.
	 */
	@Test(timeout = 30000)
	public void positionedReferencesWithoutIndex() {
		Variable<Double, ?> x = new Variable<>("x", Double.class);
		InstanceReference<Double, ?> atZero = new InstanceReference<>(x, e(0), null);

		Assert.assertTrue(known(atZero.structuralEq(new InstanceReference<>(x, e(0), null))));
		Assert.assertFalse(known(atZero.structuralEq(new InstanceReference<>(x, e(1), null))));
		Assert.assertFalse(known(atZero.structuralEq(new InstanceReference<>(x))));
		Assert.assertFalse(known(new InstanceReference<>(x).structuralEq(atZero)));
	}

	/**
	 * A collection variable whose producer is a {@link Delegated} wrapper around a computed
	 * {@link TraversableExpression} is not an opaque input, because its values are computed
	 * rather than read; a wrapper around a producer that is not traversable still is.
	 */
	@Test(timeout = 30000)
	public void delegatedComputedProducerIsNotOpaque() {
		Producer<PackedCollection> computed = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));
		Assert.assertTrue(computed instanceof TraversableExpression);

		CollectionVariable<?> delegatedComputed = new CollectionVariable<>("dc",
				new TraversalPolicy(4), (Supplier) new DelegatingSupplier(computed));
		Assert.assertFalse(delegatedComputed.isOpaqueInput());

		CollectionVariable<?> delegatedOpaque = new CollectionVariable<>("do",
				new TraversalPolicy(4), (Supplier) new DelegatingSupplier(() -> null));
		Assert.assertTrue(delegatedOpaque.isOpaqueInput());
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
	 * Differently named variables stand for the same value when their producers match,
	 * whichever kind of variable each one is and in whichever order they are compared.
	 */
	@Test(timeout = 30000)
	public void refersToSameValueByMatchingProducer() {
		Producer<PackedCollection> computed = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));
		Producer<PackedCollection> other = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));

		ArrayVariable<?> plain = new ArrayVariable<>("a", (Supplier) computed);
		CollectionVariable<?> collection = new CollectionVariable<>("b", new TraversalPolicy(4), (Supplier) computed);
		CollectionVariable<?> unrelated = new CollectionVariable<>("c", new TraversalPolicy(4), (Supplier) other);

		Assert.assertTrue(plain.hasMatchingProducer(collection));
		Assert.assertTrue(plain.refersToSameValue(collection));
		Assert.assertTrue(collection.refersToSameValue(plain));
		Assert.assertFalse(collection.refersToSameValue(unrelated));
		Assert.assertFalse(unrelated.refersToSameValue(plain));
	}

	/**
	 * Producers are compared after unwrapping any {@link Delegated} wrapper, so differently
	 * named variables that reach the same computation through wrappers, or one through a
	 * wrapper and one directly, stand for the same value; a wrapper around an unrelated
	 * computation, or around a supplier that is not a computation, does not.
	 */
	@Test(timeout = 30000)
	public void refersToSameValueThroughDelegates() {
		Producer<PackedCollection> computed = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));
		Producer<PackedCollection> other = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));

		CollectionVariable<?> wrappedA = new CollectionVariable<>("a",
				new TraversalPolicy(4), (Supplier) new DelegatingSupplier(computed));
		CollectionVariable<?> wrappedB = new CollectionVariable<>("b",
				new TraversalPolicy(4), (Supplier) new DelegatingSupplier(computed));
		CollectionVariable<?> direct = new CollectionVariable<>("c", new TraversalPolicy(4), (Supplier) computed);
		CollectionVariable<?> wrappedOther = new CollectionVariable<>("d",
				new TraversalPolicy(4), (Supplier) new DelegatingSupplier(other));
		CollectionVariable<?> wrappedOpaque = new CollectionVariable<>("e",
				new TraversalPolicy(4), (Supplier) new DelegatingSupplier(() -> null));

		Assert.assertTrue(wrappedA.hasMatchingProducer(wrappedB));
		Assert.assertTrue(wrappedA.refersToSameValue(wrappedB));
		Assert.assertTrue(wrappedA.refersToSameValue(direct));
		Assert.assertTrue(direct.refersToSameValue(wrappedB));
		Assert.assertFalse(wrappedA.refersToSameValue(wrappedOther));
		Assert.assertFalse(wrappedA.refersToSameValue(wrappedOpaque));
		Assert.assertFalse(wrappedOpaque.refersToSameValue(wrappedA));
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

	/**
	 * A minimal {@link Delegated} supplier that wraps another supplier without being
	 * traversable itself.
	 */
	private static class DelegatingSupplier implements Supplier<Object>, Delegated<Supplier<?>> {
		/** The wrapped supplier. */
		private final Supplier<?> delegate;

		/**
		 * Creates a wrapper around the given supplier.
		 *
		 * @param delegate the supplier to wrap
		 */
		DelegatingSupplier(Supplier<?> delegate) {
			this.delegate = delegate;
		}

		@Override
		public Object get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Supplier<?> getDelegate() {
			return delegate;
		}
	}
}
