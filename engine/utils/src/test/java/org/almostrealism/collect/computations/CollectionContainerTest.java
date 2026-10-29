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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.FixedEvaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link CollectionContainer}: an operation compiled once against a container reads, each time
 * it is issued, the collection set on the container at that moment.
 */
public class CollectionContainerTest extends TestSuiteBase {
	/**
	 * A compiled operation run again after another collection has been set on the container it uses
	 * reads that collection, without being compiled again.
	 */
	@Test(timeout = 30000)
	public void operationReadsCollectionSetWhenIssued() {
		TraversalPolicy shape = new TraversalPolicy(3);
		PackedCollection first = pack(1.0, 2.0, 3.0);
		PackedCollection second = pack(10.0, 20.0, 30.0);
		PackedCollection result = new PackedCollection(shape);

		CollectionContainer input = container(shape);
		Runnable doubled = a(cp(result), input.multiply(2.0)).get();

		input.set(first);
		doubled.run();
		PackedCollection expectedFirst = cp(first).multiply(2.0).evaluate();
		assertEquals(expectedFirst, result);

		input.set(second);
		doubled.run();
		PackedCollection expectedSecond = cp(second).multiply(2.0).evaluate();
		assertEquals(expectedSecond, result);
	}

	/**
	 * The evaluable of a container is a {@link FixedEvaluable}, so an operation binds its value as a
	 * resolved argument when it is issued, but not a {@link Provider}, whose value an operation may
	 * treat as known when it is compiled. Evaluating it returns whichever collection is set at that
	 * moment, rather than the one set when the evaluable was obtained.
	 */
	@Test(timeout = 30000)
	public void evaluableIsFixedContainerOfCollectionSet() {
		TraversalPolicy shape = new TraversalPolicy(4);
		PackedCollection first = new PackedCollection(shape);
		PackedCollection second = new PackedCollection(shape);

		CollectionContainer container = container(shape);
		container.set(first);

		Evaluable<PackedCollection> evaluable = container.get();
		Assert.assertTrue(evaluable instanceof FixedEvaluable);
		Assert.assertFalse(evaluable instanceof Provider);
		Assert.assertSame(first, evaluable.evaluate());

		container.set(second);
		Assert.assertSame(second, evaluable.evaluate());
	}

	/**
	 * Setting a collection cannot change the shape of a container: a collection of the same size
	 * but a different shape is rejected, and the collection set before it remains set.
	 */
	@Test(timeout = 30000)
	public void setRejectsDifferentShape() {
		PackedCollection first = new PackedCollection(new TraversalPolicy(2, 3));
		PackedCollection reshaped = new PackedCollection(new TraversalPolicy(3, 2));

		CollectionContainer container = container(new TraversalPolicy(2, 3));
		container.set(first);

		try {
			container.set(reshaped);
			Assert.fail("A collection of shape " + reshaped.getShape() + " was accepted");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("shape mismatch"));
		}

		Assert.assertEquals(new TraversalPolicy(2, 3), container.getShape());
		Assert.assertSame(first, container.get().evaluate());

		container.set(new PackedCollection(new TraversalPolicy(2, 3).traverseEach()));
	}
}
