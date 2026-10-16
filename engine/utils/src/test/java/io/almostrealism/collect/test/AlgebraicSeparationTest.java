/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.collect.test;

import io.almostrealism.collect.Algebraic;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Verifies that the linear-algebraic classification of a computation lives on
 * {@link Algebraic} and not on {@link Computable} or {@link Producer}. A computation
 * need not produce numeric values at all, so whether it is a zero, identity, diagonal or
 * row-monomial transformation, or a uniform constant, is only meaningful for the
 * computations that declare themselves {@link Algebraic}; any other computation is
 * answered conservatively by the static queries on {@link Algebraic}.
 *
 * @author Michael Murray
 */
public class AlgebraicSeparationTest extends TestSuiteBase {

	/** The queries that classify a computation as a linear transformation. */
	private static final List<String> ALGEBRAIC_QUERIES = Arrays.asList(
			"isZero", "isIdentity", "isDiagonal", "getDiagonalScalar", "isRowMonomial", "getConstant");

	/** The numeric hooks that are specific to collection producers. */
	private static final List<String> COLLECTION_HOOKS = Arrays.asList(
			"attemptMultiply", "isReduction", "isDirectlyComputable");

	/**
	 * Neither {@link Computable} nor {@link Producer} exposes an algebraic query or a
	 * numeric collection hook, while {@link Algebraic} exposes every algebraic query.
	 */
	@Test(timeout = 10000)
	public void computableHasNoNumericQueries() {
		for (Method method : Computable.class.getMethods()) {
			Assert.assertFalse("Computable must not declare " + method.getName(),
					ALGEBRAIC_QUERIES.contains(method.getName()));
			Assert.assertFalse("Computable must not declare " + method.getName(),
					COLLECTION_HOOKS.contains(method.getName()));
		}

		for (Method method : Producer.class.getMethods()) {
			Assert.assertFalse("Producer must not declare " + method.getName(),
					ALGEBRAIC_QUERIES.contains(method.getName()));
			Assert.assertFalse("Producer must not declare " + method.getName(),
					COLLECTION_HOOKS.contains(method.getName()));
		}

		List<String> algebraic = Arrays.stream(Algebraic.class.getMethods())
				.map(Method::getName).distinct().toList();
		for (String query : ALGEBRAIC_QUERIES) {
			Assert.assertTrue("Algebraic must declare " + query, algebraic.contains(query));
		}
	}

	/**
	 * A computation that is not {@link Algebraic} is never classified as a special
	 * transformation, and never reported as a collection reduction or as offering a
	 * constant-scale representation.
	 */
	@Test(timeout = 10000)
	public void nonAlgebraicComputableIsConservative() {
		Computable dog = new Computable() { };

		Assert.assertFalse(dog instanceof Algebraic);
		Assert.assertFalse(Algebraic.isZero(dog));
		Assert.assertFalse(Algebraic.isIdentity(1, dog));
		Assert.assertFalse(Algebraic.isDiagonal(1, dog));
		Assert.assertFalse(Algebraic.isRowMonomial(dog));
		Assert.assertFalse(Algebraic.getDiagonalScalar(1, dog).isPresent());
		Assert.assertFalse(Algebraic.getConstant(dog).isPresent());
		Assert.assertFalse(Algebraic.isZero(null));

		Producer<String> name = () -> args -> "dog";
		Assert.assertFalse(CollectionProducer.isReduction(name));
		Assert.assertNull(CollectionProducer.attemptMultiply(name, 3.0));
		Assert.assertNull(CollectionProducer.attemptMultiply(null, 3.0));
	}

	/**
	 * Collection computations are {@link Algebraic}, and the static queries reach the
	 * classification they declare.
	 */
	@Test(timeout = 30000)
	public void collectionComputationsAreAlgebraic() {
		CollectionProducer zeros = zeros(shape(3));
		Assert.assertTrue(zeros instanceof Algebraic);
		Assert.assertTrue(Algebraic.isZero(zeros));
		Assert.assertFalse(Algebraic.isZero(cp(pack(1.0, 2.0, 3.0))));

		OptionalDouble constant = Algebraic.getConstant(c(2.5));
		Assert.assertTrue(constant.isPresent());
		Assert.assertEquals(2.5, constant.getAsDouble(), 0.0);

		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Assert.assertTrue(CollectionProducer.isReduction(new CollectionSumComputation(cp(x))));
		Assert.assertFalse(CollectionProducer.isReduction(cp(x)));
	}
}
