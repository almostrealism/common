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
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.ConstantValue;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.expression.Sum;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexChild;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Verifies the structural primitives every {@link Expression} node relies on: the tree
 * metrics computed when a node is constructed, structural equality and its hash, structural
 * matching, the identical-children shortcut of {@link Expression#generate(List)}, the child
 * predicates of n-ary expressions, the subtree reference queries, and the queries that
 * indices and constants answer for themselves.
 *
 * <p>Compilation constructs and compares millions of nodes, so these primitives are written
 * as single passes over the children. These tests pin their results against the definitions
 * they implement, independently of how the passes are written.</p>
 */
public class ExpressionStructureTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Returns a fresh integer reference leaf with the given name, distinct from any other
	 * instance with the same name.
	 *
	 * @param name the name of the referenced value
	 * @return a new reference expression
	 */
	private Expression<Integer> ref(String name) {
		return new StaticReference<>(Integer.class, name);
	}

	/**
	 * Returns {@code (x + y) * kernel} built from fresh leaves, so that two calls produce
	 * structurally equal trees with no shared nodes.
	 *
	 * @return a new two-level expression tree
	 */
	private Expression<?> tree() {
		return Product.of(Sum.of(ref("x"), ref("y")), kernel());
	}

	/**
	 * Returns a reference to the element of the given array at a constant position, whose
	 * index is that same position.
	 *
	 * @param array    the referenced array
	 * @param position the position of the element
	 * @return a new reference to the element
	 */
	private Expression<Double> element(ArrayVariable<Double> array, int position) {
		return new InstanceReference<>(array, e(position), e(position));
	}

	/**
	 * Asserts that the depth, node count, and total compute cost of {@code e} and of every
	 * node beneath it follow from the corresponding values of their children.
	 *
	 * @param e the root of the tree to check
	 */
	private void assertMetricsFollowChildren(Expression<?> e) {
		int depth = -1;
		int nodes = 1;
		long cost = e.getComputeCost();

		for (Expression<?> child : e.getChildren()) {
			assertMetricsFollowChildren(child);
			depth = Math.max(depth, child.treeDepth());
			nodes += child.countNodes();
			cost += child.totalComputeCost();
		}

		Assert.assertEquals(depth + 1, e.treeDepth());
		Assert.assertEquals(nodes, e.countNodes());
		Assert.assertEquals(cost, e.totalComputeCost());
	}

	/**
	 * Depth, node count, and compute cost recorded at construction agree with the children
	 * at every level of a tree several levels deep.
	 */
	@Test(timeout = 30000)
	public void constructionMetricsFollowChildren() {
		Expression<?> deep = Sum.of(tree(), Product.of(ref("z"), ref("w")), kernel());
		assertMetricsFollowChildren(deep);
		Assert.assertEquals(3, deep.treeDepth());
	}

	/**
	 * A tree containing a {@link Long} value anywhere reports it, and a tree of integer
	 * values does not.
	 */
	@Test(timeout = 30000)
	public void longTypePropagatesToAncestors() {
		Expression<?> longLeaf = new StaticReference<>(Long.class, "n");
		Assert.assertTrue(longLeaf.containsLong());
		Assert.assertTrue(Sum.of(ref("x"), longLeaf).containsLong());
		Assert.assertTrue(Product.of(Sum.of(ref("x"), longLeaf), ref("y")).containsLong());
		Assert.assertFalse(tree().containsLong());
	}

	/**
	 * Independently built trees with the same structure are equal and hash alike, while a
	 * difference in a deep leaf or in an operator makes them unequal.
	 */
	@Test(timeout = 30000)
	public void structuralEqualityComparesEveryChild() {
		Expression<?> a = tree();
		Expression<?> b = tree();

		Assert.assertNotSame(a, b);
		Assert.assertEquals(a, b);
		Assert.assertEquals(b, a);
		Assert.assertEquals(a.hashCode(), b.hashCode());

		Assert.assertNotEquals(a, Product.of(Sum.of(ref("x"), ref("z")), kernel()));
		Assert.assertNotEquals(Sum.of(ref("x"), ref("y")), Product.of(ref("x"), ref("y")));
		Assert.assertNotEquals(Sum.of(ref("x"), ref("y")), Sum.of(ref("x"), ref("y"), ref("z")));
	}

	/**
	 * Two expressions match where they are the same term: leaves where they are equal, and
	 * expressions of the same kind where every child matches. Expressions of different kinds
	 * or with different numbers of children never match, and the match of two different
	 * indices is a condition on their values rather than a constant.
	 */
	@Test(timeout = 30000)
	public void expressionsMatchChildByChild() {
		Assert.assertEquals(Optional.of(true), e(3).matches(e(3)).booleanValue());
		Assert.assertEquals(Optional.of(false), e(3).matches(e(4)).booleanValue());
		Assert.assertEquals(Optional.of(false), e(3).matches(e(3.0)).booleanValue());

		Assert.assertEquals(Optional.of(true), tree().matches(tree()).booleanValue());
		Assert.assertEquals(Optional.of(false), Sum.of(ref("x"), e(1)).matches(Sum.of(ref("x"), e(2))).booleanValue());
		Assert.assertEquals(Optional.of(false), Sum.of(ref("x"), ref("y")).matches(Product.of(ref("x"), ref("y"))).booleanValue());
		Assert.assertEquals(Optional.of(false), Sum.of(ref("x"), ref("y")).matches(Sum.of(ref("x"), ref("y"), ref("z"))).booleanValue());

		DefaultIndex i = new DefaultIndex("i", 8);
		DefaultIndex j = new DefaultIndex("j", 8);
		Assert.assertTrue(i.matches(j).booleanValue().isEmpty());
		Assert.assertEquals(i.eq(j), i.matches(j));
	}

	/**
	 * A reference matches a reference to a variable of the same name wherever their indices
	 * are equal, and never matches a reference to a variable of another name, or an
	 * expression that is not a reference at all.
	 */
	@Test(timeout = 30000)
	public void referencesMatchTheSameVariableAtEqualIndices() {
		ArrayVariable<Double> a = new ArrayVariable<>(Double.class, "a", e(8));
		ArrayVariable<Double> sameName = new ArrayVariable<>(Double.class, "a", e(8));
		ArrayVariable<Double> b = new ArrayVariable<>(Double.class, "b", e(8));

		Assert.assertEquals(Optional.of(true), element(a, 2).matches(element(a, 2)).booleanValue());
		Assert.assertEquals(Optional.of(true), element(a, 2).matches(element(sameName, 2)).booleanValue());
		Assert.assertEquals(Optional.of(false), element(a, 2).matches(element(a, 3)).booleanValue());
		Assert.assertEquals(Optional.of(false), element(a, 2).matches(element(b, 2)).booleanValue());
		Assert.assertEquals(Optional.of(false), element(a, 2).matches(e(2)).booleanValue());
		Assert.assertEquals(Optional.of(false), e(2).matches(element(a, 2)).booleanValue());

		DefaultIndex i = new DefaultIndex("i", 8);
		Expression<Boolean> match = element(a, 2).matches(new InstanceReference<>(a, i, i));
		Assert.assertTrue(match.booleanValue().isEmpty());
		Assert.assertEquals(e(2).eq(i), match);
	}

	/**
	 * Regenerating a node from its own children returns the node itself, while a changed
	 * child produces a new node that includes the replacement.
	 */
	@Test(timeout = 30000)
	public void generateReusesNodeWhenChildrenAreIdentical() {
		Expression<?> sum = Sum.of(ref("x"), ref("y"));
		Assert.assertSame(sum, sum.generate(sum.getChildren()));
		Assert.assertSame(sum, sum.generate(new ArrayList<>(sum.getChildren())));

		List<Expression<?>> changed = new ArrayList<>(sum.getChildren());
		changed.set(0, ref("w"));

		Expression<?> regenerated = sum.generate(changed);
		Assert.assertNotSame(sum, regenerated);
		Assert.assertTrue(regenerated.contains(ref("w")));
		Assert.assertNotEquals(sum, regenerated);
	}

	/**
	 * An n-ary expression is possibly negative exactly when one of its operands is, and has
	 * a value for given index values exactly when all of its operands do.
	 */
	@Test(timeout = 30000)
	public void naryPredicatesConsultEveryOperand() {
		Assert.assertFalse(Sum.of(kernel(), e(3)).isPossiblyNegative());
		Assert.assertTrue(Sum.of(kernel(), e(-3)).isPossiblyNegative());

		Expression<?> offset = Sum.of(kernel(), e(3));
		Assert.assertTrue(offset.isValue(new IndexValues(0)));
		Assert.assertFalse(offset.isValue(new IndexValues()));
		Assert.assertFalse(Sum.of(kernel(), ref("x")).isValue(new IndexValues(0)));
	}

	/**
	 * The reference queries find subexpressions, indices, and names anywhere in the
	 * subtree, and report a tree as loop-variant exactly when it refers to something the
	 * loop changes.
	 */
	@Test(timeout = 30000)
	public void referenceQueriesSearchTheWholeSubtree() {
		Expression<?> tree = tree();

		Assert.assertTrue(tree.contains(Sum.of(ref("x"), ref("y"))));
		Assert.assertFalse(tree.contains(ref("z")));
		Assert.assertTrue(tree.containsIndex(kernel()));
		Assert.assertFalse(Sum.of(ref("x"), ref("y")).containsIndex(kernel()));
		Assert.assertTrue(tree.containsReference(Set.of("y")));
		Assert.assertFalse(tree.containsReference(Set.of("z")));

		Set<String> names = new HashSet<>();
		tree.collectReferencedNames(names);
		Assert.assertTrue(names.containsAll(Set.of("x", "y")));

		Assert.assertFalse(tree.isLoopInvariant(Set.of("x"), Collections.emptyList(), Collections.emptyList()));
		Assert.assertTrue(tree.isLoopInvariant(Set.of("z"), Collections.emptyList(), Collections.emptyList()));
		Assert.assertFalse(tree.isLoopInvariant(Set.of("z"), Collections.emptyList(), List.of(kernel())));
	}

	/**
	 * An index answers the reference queries for itself: a named index and a child index
	 * each contain an index with their own name and contribute that name to the referenced
	 * names, and a child index also reaches the indices it is composed of.
	 */
	@Test(timeout = 30000)
	public void indicesAnswerReferenceQueriesForThemselves() {
		DefaultIndex row = new DefaultIndex("row", 4);
		DefaultIndex col = new DefaultIndex("col", 3);
		IndexChild cell = Index.child(row, col);
		Expression<?> scaled = Product.of(cell, ref("x"));

		Assert.assertTrue(row.containsIndex(new DefaultIndex("row")));
		Assert.assertFalse(row.containsIndex(col));
		Assert.assertTrue(cell.containsIndex(cell));
		Assert.assertTrue(cell.containsIndex(row));
		Assert.assertTrue(cell.containsIndex(col));
		Assert.assertFalse(cell.containsIndex(new DefaultIndex("other")));
		Assert.assertTrue(scaled.containsIndex(cell));

		Set<String> names = new HashSet<>();
		scaled.collectReferencedNames(names);
		Assert.assertEquals(Set.of(cell.getName(), "row", "col", "x"), names);
	}

	/**
	 * An index substitutes itself: a named index, a kernel index, and a child index are each
	 * replaced by the given expression when the index being replaced has their name, are left
	 * unchanged by an index they do not contain, and a child index passes a substitution for
	 * one of the indices it is composed of on to that index.
	 */
	@Test(timeout = 30000)
	public void indicesSubstituteThemselves() {
		DefaultIndex row = new DefaultIndex("row", 4);
		DefaultIndex col = new DefaultIndex("col", 3);
		IndexChild cell = Index.child(row, col);
		Expression<?> replacement = ref("y");

		Assert.assertSame(replacement, row.withIndex(new DefaultIndex("row"), replacement));
		Assert.assertSame(row, row.withIndex(col, replacement));
		Assert.assertSame(replacement, kernel().withIndex(kernel(), replacement));
		Assert.assertSame(replacement, cell.withIndex(cell, replacement));
		Assert.assertSame(cell, cell.withIndex(new DefaultIndex("other"), replacement));
		Assert.assertEquals(OptionalInt.of(11), cell.withIndex(row, e(3)).withIndex(col, e(2)).intValue());
	}

	/**
	 * An index reports itself as the only index it references, and needs every one of its
	 * values, and no value of any other index, to produce all of its values.
	 */
	@Test(timeout = 30000)
	public void indicesAnswerIndexQueriesForThemselves() {
		DefaultIndex row = new DefaultIndex("row", 4);
		DefaultIndex col = new DefaultIndex("col", 3);
		IndexChild cell = Index.child(row, col);

		Assert.assertEquals(Set.of(row), row.getIndices());
		Assert.assertEquals(Set.of(kernel()), kernel().getIndices());
		Assert.assertEquals(Set.of(cell), cell.getIndices());
		Assert.assertEquals(Set.of(cell), Product.of(cell, ref("x")).getIndices());
		Assert.assertTrue(ref("x").getIndices().isEmpty());

		Assert.assertEquals(Set.of(0, 1, 2, 3), row.getIndexOptions(new DefaultIndex("row", 4)).orElseThrow());
		Assert.assertTrue(row.getIndexOptions(col).orElseThrow().isEmpty());
		Assert.assertTrue(new DefaultIndex("free").getIndexOptions(new DefaultIndex("free")).isEmpty());
	}

	/**
	 * Indices and constants are never targets of series simplification, which could only
	 * reproduce them, while any other expression defers to the configured policy.
	 */
	@Test(timeout = 30000)
	public void indicesAndConstantsAreNotSeriesTargets() {
		DefaultIndex col = new DefaultIndex("col", 3);

		Assert.assertFalse(col.isSeriesSimplificationTarget(0));
		Assert.assertFalse(kernel().isSeriesSimplificationTarget(0));
		Assert.assertFalse(Index.child(new DefaultIndex("row", 4), col).isSeriesSimplificationTarget(0));
		Assert.assertFalse(Index.child(kernel(), col).isSeriesSimplificationTarget(0));
		Assert.assertFalse(e(3).isSeriesSimplificationTarget(0));

		Expression<?> tree = tree();
		Assert.assertEquals(ScopeSettings.isSeriesSimplificationTarget(tree, 0), tree.isSeriesSimplificationTarget(0));
	}

	/**
	 * An integer quotient evaluates to an {@link Integer}, the Java type of its result type,
	 * even though the division is carried out in {@code long} arithmetic.
	 */
	@Test(timeout = 30000)
	public void integerQuotientEvaluatesToItsResultType() {
		Assert.assertEquals(Integer.valueOf(3), kernel().divide(e(2)).value(new IndexValues(7)));
	}

	/**
	 * A constant decides equality by its own value: the same kind of constant holding the
	 * same value is equal and hashes alike, while a different value, a different kind of
	 * constant, or a constant holding a value of arbitrary type is simply unequal.
	 */
	@Test(timeout = 30000)
	public void constantsCompareByTheirOwnValue() {
		Assert.assertTrue(new BooleanConstant(true).equals(new BooleanConstant(true)));
		Assert.assertEquals(new BooleanConstant(true).hashCode(), new BooleanConstant(true).hashCode());
		Assert.assertFalse(new BooleanConstant(true).equals(new BooleanConstant(false)));
		Assert.assertFalse(new BooleanConstant(true).equals(e(1)));
		Assert.assertFalse(new BooleanConstant(true).equals(new ConstantValue<>(Boolean.class, true)));
		Assert.assertTrue(Constant.of(1.5f).equals(Constant.of(1.5f)));
		Assert.assertFalse(Constant.of(1.5f).equals(Constant.of(2.5f)));
	}

	/**
	 * Every constant renders its own value, including one holding a value of a type that
	 * has no dedicated constant expression, and needs no parentheses as an operand.
	 */
	@Test(timeout = 30000)
	public void constantsRenderTheirOwnValue() {
		LanguageOperations lang = new LanguageOperationsStub();

		Assert.assertEquals("true", new BooleanConstant(true).getExpression(lang));
		Assert.assertEquals("1.5", Constant.of(1.5f).getExpression(lang));
		Assert.assertEquals("7", Constant.of((short) 7).getWrappedExpression(lang));
	}
}
