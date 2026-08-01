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
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.scope.LeafInternTable;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link LeafInternTable} and its hook in the
 * {@link Expression} constructor.
 *
 * <p>The tests build {@link Expression} trees with the leaf-interning flag
 * toggled and check that the children-array canonicalisation produces the
 * expected identity-sharing without changing structural equality.</p>
 */
public class LeafInternTableTests extends TestSuiteBase implements ExpressionFeatures {

	/** Original flag value, restored in {@link #tearDown()}. */
	private boolean previousFlag;

	/**
	 * Snapshots the current flag value and clears the table so each test starts
	 * from a clean baseline.
	 */
	@Before
	public void setUp() {
		previousFlag = ScopeSettings.enableLeafInterning;
		LeafInternTable.clear();
	}

	/**
	 * Restores the flag and clears the table so a test that left interning on
	 * cannot contaminate the next.
	 */
	@After
	public void tearDown() {
		ScopeSettings.enableLeafInterning = previousFlag;
		LeafInternTable.clear();
	}

	/**
	 * With the flag off, two parents that each receive a freshly-constructed
	 * leaf with the same value retain their own distinct leaf instances. The
	 * table is not touched.
	 *
	 * <p>The non-foldable {@link KernelIndex} child keeps {@code Sum.of} from
	 * collapsing the whole expression to a single {@code IntegerConstant}.</p>
	 */
	@Test(timeout = 10000)
	public void flagOffPreservesDistinctLeavesAcrossParents() {
		ScopeSettings.enableLeafInterning = false;

		KernelIndex idx = new KernelIndex();
		Expression<?> leafA = new IntegerConstant(7);
		Expression<?> leafB = new IntegerConstant(7);
		Expression<?> parent1 = Sum.of(idx, leafA);
		Expression<?> parent2 = Sum.of(idx, leafB);

		Expression<?> child1 = findInternableLeaf(parent1.getChildren());
		Expression<?> child2 = findInternableLeaf(parent2.getChildren());

		Assert.assertNotSame("test inputs must be distinct instances", leafA, leafB);
		Assert.assertEquals("inputs are still structurally equal", leafA, leafB);
		Assert.assertNotSame("parents must hold distinct child leaves when flag is off",
				child1, child2);
		Assert.assertEquals("table should not have grown when flag is off",
				0, LeafInternTable.size());
	}

	/**
	 * With the flag on, two parents that each receive a freshly-constructed
	 * leaf with the same value end up referencing the same canonical leaf
	 * instance.
	 */
	@Test(timeout = 10000)
	public void flagOnCollapsesDuplicateLeavesAcrossParents() {
		ScopeSettings.enableLeafInterning = true;

		KernelIndex idx = new KernelIndex();
		Expression<?> leafA = new IntegerConstant(7);
		Expression<?> leafB = new IntegerConstant(7);
		Expression<?> parent1 = Sum.of(idx, leafA);
		Expression<?> parent2 = Sum.of(idx, leafB);

		Expression<?> canon1 = findInternableLeaf(parent1.getChildren());
		Expression<?> canon2 = findInternableLeaf(parent2.getChildren());

		Assert.assertSame("parents should share the canonical leaf when flag is on",
				canon1, canon2);
		Assert.assertEquals("the canonical leaf is structurally equal to both inputs",
				new IntegerConstant(7), canon1);
	}

	/**
	 * Identity collapse must not change observable semantics: the parents
	 * remain {@link Expression#equals} to the same fresh re-construction.
	 */
	@Test(timeout = 10000)
	public void interningPreservesStructuralEquality() {
		ScopeSettings.enableLeafInterning = true;

		KernelIndex idx = new KernelIndex();
		Expression<?> parentA = Sum.of(idx, new IntegerConstant(5));
		Expression<?> parentB = Sum.of(idx, new IntegerConstant(5));

		Assert.assertEquals("interning must preserve structural equality",
				parentA, parentB);
	}

	/**
	 * Two {@link KernelIndex} instances with the same axis and the same (null)
	 * context collapse to a shared canonical when stored in parent children.
	 * {@code KernelIndex} interning is routed through a strict secondary key
	 * (axis + context-by-reference) because the structural {@code compare()}
	 * intentionally ignores context — see {@code LeafInternTable}.
	 */
	@Test(timeout = 10000)
	public void kernelIndicesAreInternedByStrictKey() {
		ScopeSettings.enableLeafInterning = true;

		KernelIndex idxA = new KernelIndex();
		KernelIndex idxB = new KernelIndex();
		Expression<?> parent1 = Sum.of(idxA, new IntegerConstant(1));
		Expression<?> parent2 = Sum.of(idxB, new IntegerConstant(1));

		Expression<?> canon1 = parent1.getChildren().stream()
				.filter(c -> c instanceof KernelIndex)
				.findFirst().orElseThrow();
		Expression<?> canon2 = parent2.getChildren().stream()
				.filter(c -> c instanceof KernelIndex)
				.findFirst().orElseThrow();

		Assert.assertNotSame("test inputs must be distinct instances", idxA, idxB);
		Assert.assertSame("parents should share the canonical KernelIndex when flag is on",
				canon1, canon2);
	}

	/**
	 * {@link DoubleConstant}s share the same canonicalisation path; two
	 * freshly-constructed doubles with the same value collapse to one
	 * canonical instance when stored in parent children.
	 */
	@Test(timeout = 10000)
	public void doubleConstantsAreInterned() {
		ScopeSettings.enableLeafInterning = true;

		KernelIndex idx = new KernelIndex();
		Expression<?> parent1 = Sum.of(idx, new DoubleConstant(2.5));
		Expression<?> parent2 = Sum.of(idx, new DoubleConstant(2.5));

		Expression<?> canon1 = parent1.getChildren().stream()
				.filter(c -> c instanceof DoubleConstant).findFirst().orElseThrow();
		Expression<?> canon2 = parent2.getChildren().stream()
				.filter(c -> c instanceof DoubleConstant).findFirst().orElseThrow();

		Assert.assertSame("parents should share the canonical DoubleConstant",
				canon1, canon2);
	}

	/**
	 * When the cap is reached, new entries are not added but lookups still
	 * return existing canonicals. The graceful-degradation contract:
	 * "intern what we already saw, leave new things alone."
	 *
	 * <p>Sizes are not asserted directly because {@link LeafInternTable#size()}
	 * counts both the primary {@code Constant}/{@code InstanceReference} table
	 * and the strict {@code KernelIndex} table; instead, the contract is
	 * verified by checking that an over-cap entry is <em>not</em> shared
	 * across parents while a within-cap entry <em>is</em>.</p>
	 */
	@Test(timeout = 10000)
	public void tableRespectsMaxSizeCap() {
		ScopeSettings.enableLeafInterning = true;
		int previousMax = ScopeSettings.maxLeafInternTableSize;
		ScopeSettings.maxLeafInternTableSize = 1;

		try {
			KernelIndex idx = new KernelIndex();

			// First IntegerConstant fills the primary table to its cap of 1.
			Expression<?> firstParent = Sum.of(idx, new IntegerConstant(1));
			Expression<?> firstCanonical = firstParent.getChildren().stream()
					.filter(c -> c instanceof IntegerConstant).findFirst().orElseThrow();

			// A repeat of the existing value still gets its canonical back.
			Expression<?> repeat = Sum.of(idx, new IntegerConstant(1));
			Expression<?> repeatChild = repeat.getChildren().stream()
					.filter(c -> c instanceof IntegerConstant).findFirst().orElseThrow();
			Assert.assertSame("within-cap canonical of 1 still resolves",
					firstCanonical, repeatChild);

			// A second distinct value cannot be added; two parents that reference
			// "2" do not collapse onto a shared canonical.
			Expression<?> parentA = Sum.of(idx, new IntegerConstant(2));
			Expression<?> parentB = Sum.of(idx, new IntegerConstant(2));
			Expression<?> childA = parentA.getChildren().stream()
					.filter(c -> c instanceof IntegerConstant).findFirst().orElseThrow();
			Expression<?> childB = parentB.getChildren().stream()
					.filter(c -> c instanceof IntegerConstant).findFirst().orElseThrow();
			Assert.assertNotSame("over-cap entries must not be added",
					childA, childB);
		} finally {
			ScopeSettings.maxLeafInternTableSize = previousMax;
		}
	}

	/**
	 * Picks the first child that is an {@link IntegerConstant}. Used by tests
	 * that need to fish the interned leaf back out of a parent's children list
	 * without depending on a specific ordering. Restricted to
	 * {@code IntegerConstant} rather than any internable leaf because
	 * {@link KernelIndex} is now also internable and would otherwise be
	 * returned first.
	 *
	 * @param children the parent's children list
	 * @return the first {@code IntegerConstant} child
	 */
	private static Expression<?> findInternableLeaf(List<Expression<?>> children) {
		return children.stream()
				.filter(c -> c instanceof IntegerConstant)
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"expected at least one IntegerConstant child in " + children));
	}
}
