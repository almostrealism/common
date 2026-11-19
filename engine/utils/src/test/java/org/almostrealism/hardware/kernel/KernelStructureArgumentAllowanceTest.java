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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tests that the kernel structure resources add no more kernel arguments than the allowance they
 * are given. A kernel judged to fit its compute context's argument limit is compiled with every
 * argument standalone, so the arguments these resources create while the kernel is generated must
 * fit in the room its structural bound leaves under that limit.
 */
public class KernelStructureArgumentAllowanceTest extends TestSuiteBase implements ExpressionFeatures {
	/** Width at which {@code generateReordering} rewrites; matches {@code minimumChildren}. */
	private static final int WIDTH = 16;

	/** Fixed kernel count of the generators under test. */
	private static final int COUNT = 32;

	/** Reused so {@link Expression#withIndex(io.almostrealism.kernel.Index, int)} matches the guards. */
	private final KernelIndex index = new KernelIndex();

	/**
	 * A generator creates at most its maximum number of traversal operations: past it, a distinct
	 * expression is returned unchanged and no argument is created, while an expression already
	 * reordered still resolves to its existing argument.
	 */
	@Test(timeout = 60000)
	public void generatorStopsAtItsMaximum() {
		int[] created = new int[1];
		KernelTraversalOperationGenerator generator =
				new KernelTraversalOperationGenerator(COUNT, true, 2, countingFactory(created));

		Assert.assertFalse(generator.generateReordering(maskedSum(0)) instanceof Sum);
		Assert.assertFalse(generator.generateReordering(maskedSum(1)) instanceof Sum);
		Assert.assertEquals(2, created[0]);

		Expression<?> third = maskedSum(2);
		Assert.assertSame(third, generator.generateReordering(third));
		Assert.assertEquals(2, created[0]);

		Assert.assertFalse(generator.generateReordering(maskedSum(0)) instanceof Sum);
		Assert.assertEquals(2, created[0]);
	}

	/**
	 * A generator with no room for any operation leaves every expression as it is.
	 */
	@Test(timeout = 30000)
	public void generatorWithZeroMaximumCreatesNothing() {
		int[] created = new int[1];
		KernelTraversalOperationGenerator generator =
				new KernelTraversalOperationGenerator(COUNT, true, 0, countingFactory(created));

		Expression<?> sum = maskedSum(0);
		Assert.assertSame(sum, generator.generateReordering(sum));
		Assert.assertEquals(0, created[0]);
	}

	/**
	 * A negative maximum is refused, both by the constructor and by the factory method.
	 */
	@Test(timeout = 30000)
	public void negativeMaximumIsRejected() {
		try {
			new KernelTraversalOperationGenerator(COUNT, true, -1, countingFactory(new int[1]));
			Assert.fail("a generator with a negative maximum was created");
		} catch (IllegalArgumentException expected) {
			log("constructor refused -1");
		}

		try (PackedCollection input = new PackedCollection(shape(COUNT));
			 PackedCollection weights = new PackedCollection(shape(COUNT))) {
			try {
				KernelTraversalOperationGenerator.create(weightedSum(input, weights), -1, countingFactory(new int[1]));
				Assert.fail("create accepted a negative maximum");
			} catch (IllegalArgumentException expected) {
				log("create refused -1");
			}
		}
	}

	/**
	 * With no allowance the structure context creates no series cache and a traversal generator
	 * that reorders nothing; with an allowance of one, the series cache takes it and the generator
	 * still reorders nothing; with two, the generator may create exactly one operation.
	 */
	@Test(timeout = 60000)
	public void structureContextKeepsWithinAllowance() {
		try (PackedCollection input = new PackedCollection(shape(COUNT));
			 PackedCollection weights = new PackedCollection(shape(COUNT))) {
			for (int allowance = 0; allowance <= 2; allowance++) {
				int[] created = new int[1];
				CompiledKernelStructureContext context =
						new CompiledKernelStructureContext(weightedSum(input, weights), 1);

				try {
					context.prepareResources(countingProvider(created), allowance);
					Assert.assertEquals("allowance=" + allowance,
							allowance >= CompiledKernelStructureContext.SERIES_CACHE_ARGUMENTS,
							context.getSeriesProvider() != null);

					KernelTraversalProvider traversal = context.getTraversalProvider();
					Assert.assertNotNull(traversal);

					int expectedOperations = Math.max(0, allowance - CompiledKernelStructureContext.SERIES_CACHE_ARGUMENTS);
					for (int i = 0; i < 3; i++) {
						Expression<?> sum = maskedSum(i);
						Expression<?> result = traversal.generateReordering(sum);
						if (i < expectedOperations) {
							Assert.assertFalse("allowance=" + allowance + " i=" + i, result instanceof Sum);
						} else {
							Assert.assertSame("allowance=" + allowance + " i=" + i, sum, result);
						}
					}

					Assert.assertEquals("allowance=" + allowance, expectedOperations, created[0]);
				} finally {
					context.destroy();
				}
			}
		}
	}

	/**
	 * Preparing resources a second time, even with a different allowance, changes nothing: a
	 * compiled kernel has exactly one set of structure resources.
	 */
	@Test(timeout = 30000)
	public void secondPreparationIsANoOp() {
		try (PackedCollection input = new PackedCollection(shape(COUNT));
			 PackedCollection weights = new PackedCollection(shape(COUNT))) {
			CompiledKernelStructureContext context =
					new CompiledKernelStructureContext(weightedSum(input, weights), 1);

			try {
				ArgumentProvider provider = countingProvider(new int[1]);
				context.prepareResources(provider, 0);
				KernelTraversalProvider traversal = context.getTraversalProvider();

				context.prepareResources(provider, Integer.MAX_VALUE);
				Assert.assertNull(context.getSeriesProvider());
				Assert.assertSame(traversal, context.getTraversalProvider());
			} finally {
				context.destroy();
			}
		}
	}

	/**
	 * A negative allowance is refused.
	 */
	@Test(timeout = 30000)
	public void negativeAllowanceIsRejected() {
		try (PackedCollection input = new PackedCollection(shape(COUNT));
			 PackedCollection weights = new PackedCollection(shape(COUNT))) {
			CompiledKernelStructureContext context =
					new CompiledKernelStructureContext(weightedSum(input, weights), 1);

			try {
				context.prepareResources(countingProvider(new int[1]), -1);
				Assert.fail("a negative allowance was accepted");
			} catch (IllegalArgumentException expected) {
				log("refused allowance -1");
			} finally {
				context.destroy();
			}
		}
	}

	/**
	 * Builds a fixed-count computation that supports kernel structure.
	 *
	 * @param input   the input
	 * @param weights the weights
	 * @return the computation
	 */
	private WeightedSumComputation weightedSum(PackedCollection input, PackedCollection weights) {
		return new WeightedSumComputation(shape(COUNT), shape(COUNT), shape(COUNT),
				shape(1), shape(1), cp(input), cp(weights));
	}

	/**
	 * Builds a {@link Sum} of {@link #WIDTH} single-index-masked terms whose values are offset by
	 * {@code variant}, so that different variants are structurally distinct expressions.
	 *
	 * @param variant the offset distinguishing this expression from the others
	 * @return the constructed sum expression
	 */
	private Expression<?> maskedSum(int variant) {
		Expression<?>[] terms = new Expression[WIDTH];
		for (int j = 0; j < WIDTH; j++) {
			terms[j] = Mask.of(index.eq(j), e((double) (j + 1 + 100 * variant)));
		}

		return Sum.of(terms);
	}

	/**
	 * Returns a variable factory that counts the variables it creates.
	 *
	 * @param created the single-element counter to increment
	 * @return the factory
	 */
	private static Function<Producer<?>, ArrayVariable<?>> countingFactory(int[] created) {
		return p -> {
			created[0]++;
			return new ArrayVariable<>(Double.class, "reorderTable" + created[0],
					(Expression) new IntegerConstant(COUNT));
		};
	}

	/**
	 * Returns an argument provider that counts the arguments it creates.
	 *
	 * @param created the single-element counter to increment
	 * @return the provider
	 */
	private static ArgumentProvider countingProvider(int[] created) {
		return new ArgumentProvider() {
			@Override
			public <T> ArrayVariable<T> getArgument(Supplier<Evaluable<? extends T>> input,
													ArrayVariable<T> delegate, int delegateOffset) {
				created[0]++;
				return new ArrayVariable(Double.class, "argument" + created[0],
						(Expression) new IntegerConstant(COUNT));
			}
		};
	}
}
