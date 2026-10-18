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
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.scope.Variable;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the guard of the gather analysis in
 * {@link TraversableExpression#uniqueNonZeroOffset}: a traversable expression whose
 * value has no symbolic form ({@link TraversableExpression#getValueAt(Expression)
 * getValueAt} returns {@code null}, as a reshape of a non-traversable producer does)
 * or is only a reference to a variable declines the analysis, while one with a
 * symbolic value is analysed entry by entry.
 */
public class TraversableExpressionGatherTest extends TestSuiteBase implements ExpressionFeatures {

	/** Row (global) index of the analysed gather. */
	private final DefaultIndex global = new DefaultIndex("gatherGlobal", 3);

	/** Column (local) index of the analysed gather. */
	private final DefaultIndex local = new DefaultIndex("gatherLocal", 3);

	/**
	 * The flat target position of each (global, local) pair, which visits every entry of
	 * a 3x3 matrix once, so that no column is the same for all rows.
	 *
	 * @return {@code global * 3 + local}
	 */
	private Expression<?> target() {
		return global.multiply(3).add(local);
	}

	/**
	 * A traversable expression without a symbolic value declines the analysis rather
	 * than failing on the missing value.
	 */
	@Test(timeout = 30000)
	public void missingValueDeclines() {
		TraversableExpression<Double> deferred = index -> null;
		Assert.assertNull(deferred.uniqueNonZeroOffset(global, local, target()));
	}

	/**
	 * A traversable expression whose value is a reference to a variable has no
	 * per-entry structure to analyse, so no offset is produced.
	 */
	@Test(timeout = 30000)
	public void variableReferenceDeclines() {
		Variable<Double, ?> variable = new Variable<>("gatherValue", Double.class);
		TraversableExpression<Double> reference = index -> new InstanceReference<>(variable);

		Assert.assertNull(reference.uniqueNonZeroOffset(global, local, target()));
	}

	/**
	 * A symbolic value that is non-zero only where the flat position is a multiple of
	 * four (the diagonal of the 3x3 matrix) yields the column of that single non-zero
	 * entry for every row.
	 */
	@Test(timeout = 30000)
	public void symbolicValueIsAnalysed() {
		TraversableExpression<Double> diagonal = index ->
				conditional(index.imod(4).eq(0), e(1.0), e(0.0));

		Expression<?> offset = diagonal.uniqueNonZeroOffset(global, local, target());
		Assert.assertNotNull("each row has exactly one non-zero entry", offset);

		for (int row = 0; row < 3; row++) {
			Assert.assertEquals("offset of row " + row, row,
					offset.withIndex(global, row).simplify().intValue().getAsInt());
		}
	}
}
