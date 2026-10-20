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

package io.almostrealism.scope.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Verifies how variables are identified with one another, through
 * {@link Variable#isSameAs(Variable)} and {@link Variable#hasMatchingProducer(Variable)},
 * and how {@link ArrayVariable#getTraversableExpression()} follows delegates.
 */
public class VariableIdentityTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Variables are the same by name by default.
	 */
	@Test(timeout = 30000)
	public void sameByName() {
		Variable<Double, ?> x = new Variable<>("x", Double.class);

		Assert.assertTrue(x.isSameAs(new Variable<>("x", Double.class)));
		Assert.assertFalse(x.isSameAs(new Variable<>("y", Double.class)));
	}

	/**
	 * Differently named variables are the same when their producers match, whichever kind
	 * of variable each one is and in whichever order they are compared.
	 */
	@Test(timeout = 30000)
	public void sameByMatchingProducer() {
		Producer<PackedCollection> computed = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));
		Producer<PackedCollection> other = cp(new PackedCollection(4)).add(cp(new PackedCollection(4)));

		ArrayVariable<?> plain = new ArrayVariable<>("a", (Supplier) computed);
		CollectionVariable<?> collection = new CollectionVariable<>("b", new TraversalPolicy(4), (Supplier) computed);
		CollectionVariable<?> unrelated = new CollectionVariable<>("c", new TraversalPolicy(4), (Supplier) other);

		Assert.assertTrue(plain.hasMatchingProducer(collection));
		Assert.assertTrue(plain.isSameAs(collection));
		Assert.assertTrue(collection.isSameAs(plain));
		Assert.assertFalse(collection.isSameAs(unrelated));
		Assert.assertFalse(unrelated.isSameAs(plain));
	}

	/**
	 * Producers are compared through their {@link Delegated} wrappers, so differently named
	 * variables that reach the same computation through wrappers, or one through a wrapper
	 * and one directly, are the same; a wrapper around an unrelated computation, or around a
	 * supplier that is not a computation, is not.
	 */
	@Test(timeout = 30000)
	public void sameThroughDelegates() {
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
		Assert.assertTrue(wrappedA.isSameAs(wrappedB));
		Assert.assertTrue(wrappedA.isSameAs(direct));
		Assert.assertTrue(direct.isSameAs(wrappedB));
		Assert.assertFalse(wrappedA.isSameAs(wrappedOther));
		Assert.assertFalse(wrappedA.isSameAs(wrappedOpaque));
		Assert.assertFalse(wrappedOpaque.isSameAs(wrappedA));
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
