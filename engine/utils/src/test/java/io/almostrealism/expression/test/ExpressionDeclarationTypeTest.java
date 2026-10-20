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

import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies each branch of {@link Expression#getDeclarationType()} directly: the type
 * of a variable that an expression can be hoisted into without changing its arithmetic.
 */
public class ExpressionDeclarationTypeTest extends TestSuiteBase {

	/** A non-integer expression keeps its own type, even when it refers to the kernel index. */
	@Test(timeout = 30000)
	public void nonIntegerTypeIsKept() {
		Assert.assertEquals(Double.class, new DoubleConstant(2.5).getDeclarationType());

		Expression<?> scaled = new KernelIndex().withLimit(16).toDouble().multiply(0.5);
		Assert.assertEquals(Double.class, scaled.getType());
		Assert.assertEquals(Double.class, scaled.getDeclarationType());
	}

	/** An integer expression of the kernel index is 64-bit, however small its bounds. */
	@Test(timeout = 30000)
	public void kernelIndexIsLong() {
		Expression<?> kernel = new KernelIndex().withLimit(4);
		Assert.assertEquals(Integer.class, kernel.getType());
		Assert.assertEquals(Long.class, kernel.getDeclarationType());

		Expression<?> nested = new DefaultIndex("j", 8).add(new KernelIndex().withLimit(4).imod(2));
		Assert.assertTrue(nested.containsKernelIndex());
		Assert.assertEquals(Long.class, nested.getDeclarationType());
	}

	/** An integer expression bounded above and below within 32 bits stays 32-bit. */
	@Test(timeout = 30000)
	public void boundedIntegerIsKept() {
		Expression<?> index = new DefaultIndex("i", 4096).imod(64);
		Assert.assertFalse(index.containsKernelIndex());
		Assert.assertEquals(Integer.class, index.getType());
		Assert.assertTrue(index.upperBound(null).getAsLong() == 63);
		Assert.assertTrue(index.lowerBound(null).getAsLong() == 0);
		Assert.assertEquals(Integer.class, index.getDeclarationType());
	}

	/** A bounded integer expression whose upper bound exceeds 32 bits is promoted to 64-bit. */
	@Test(timeout = 30000)
	public void boundsBeyondIntegerRangeAreLong() {
		Expression<?> upper = new DefaultIndex("i", 65536).multiply(65536);
		Assert.assertEquals(Integer.class, upper.getType());
		Assert.assertTrue(upper.upperBound(null).getAsLong() > Integer.MAX_VALUE);
		Assert.assertEquals(Long.class, upper.getDeclarationType());
	}

	/**
	 * An integer expression whose bounds cannot be determined is promoted to 64-bit,
	 * whether it is a bare reference or a product of an index, whose lower bound the
	 * analysis does not derive even when its upper bound fits in 32 bits.
	 */
	@Test(timeout = 30000)
	public void unboundedIntegerIsLong() {
		Expression<?> ref = new StaticReference<>(Integer.class, "n");
		Assert.assertTrue(ref.upperBound(null).isEmpty() || ref.lowerBound(null).isEmpty());
		Assert.assertEquals(Long.class, ref.getDeclarationType());

		Expression<?> product = new DefaultIndex("i", 32768).multiply(65535);
		Assert.assertEquals(Integer.class, product.getType());
		Assert.assertEquals(32767L * 65535L, product.upperBound(null).getAsLong());
		Assert.assertTrue(product.lowerBound(null).isEmpty());
		Assert.assertEquals(Long.class, product.getDeclarationType());

		Expression<?> negative = new DefaultIndex("i", 16).multiply(-3);
		Assert.assertEquals(Integer.class, negative.getType());
		Assert.assertTrue(negative.lowerBound(null).isEmpty());
		Assert.assertEquals(Long.class, negative.getDeclarationType());
	}
}
