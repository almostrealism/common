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
import io.almostrealism.expression.ArithmeticGenerator;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.ConstantValue;
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.expression.Sum;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;
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
	 * Returns the sum of the given operands, which must not simplify to anything other
	 * than a {@link Sum}.
	 *
	 * @param operands the operands
	 * @return the sum
	 */
	private Sum<?> sum(Expression<?>... operands) {
		Expression<?> result = Sum.of(operands);
		Assert.assertTrue(result instanceof Sum);
		return (Sum<?>) result;
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
	 * A reference without an index, such as the whole-variable reference a variable gives out
	 * for itself, matches another such reference to the same variable and never matches a
	 * reference to one of its elements.
	 */
	@Test(timeout = 30000)
	public void referencesWithoutIndexMatchByReferent() {
		ArrayVariable<Double> a = new ArrayVariable<>(Double.class, "a", e(8));
		Variable<Integer, ?> s = Variable.integer("s");

		Assert.assertEquals(Optional.of(true), a.ref().matches(a.ref()).booleanValue());
		Assert.assertEquals(Optional.of(true), s.ref().matches(s.ref()).booleanValue());
		Assert.assertEquals(Optional.of(false), a.ref().matches(element(a, 2)).booleanValue());
		Assert.assertEquals(Optional.of(false), element(a, 2).matches(a.ref()).booleanValue());
	}

	/**
	 * A reference is rendered by the variable it refers to: an array element is dereferenced
	 * at its position, a whole array and a variable that is not an array are rendered by name.
	 */
	@Test(timeout = 30000)
	public void referencesAreRenderedByTheirReferent() {
		LanguageOperations lang = new LanguageOperationsStub();
		ArrayVariable<Double> a = new ArrayVariable<>(Double.class, "a", e(8));

		Assert.assertEquals("a[2]", element(a, 2).getExpression(lang));
		Assert.assertEquals("a", a.ref().getExpression(lang));
		Assert.assertEquals("s", Variable.integer("s").ref().getExpression(lang));
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
	 * A child index is opaque to index-domain analysis, like any other index: producing all
	 * of its values requires every one of its own positions, up to the product of the limits
	 * it is composed of, and no value of the parent or child index. Expressions over a child
	 * index combine that domain, and an unbounded child index has no enumerable domain.
	 */
	@Test(timeout = 30000)
	public void childIndexAnswersIndexOptionsForItself() {
		DefaultIndex row = new DefaultIndex("row", 4);
		DefaultIndex col = new DefaultIndex("col", 3);
		IndexChild cell = Index.child(row, col);

		Set<Integer> positions = new HashSet<>();
		for (int i = 0; i < 12; i++) positions.add(i);

		Assert.assertEquals(positions, cell.getIndexOptions(cell).orElseThrow());
		Assert.assertEquals(positions, Product.of(cell, ref("x")).getIndexOptions(cell).orElseThrow());
		Assert.assertTrue(cell.getIndexOptions(row).orElseThrow().isEmpty());
		Assert.assertTrue(cell.getIndexOptions(col).orElseThrow().isEmpty());
		Assert.assertTrue(cell.getIndexOptions(new DefaultIndex("other", 5)).orElseThrow().isEmpty());

		IndexChild unbounded = Index.child(new DefaultIndex("free"), col);
		Assert.assertTrue(unbounded.getIndexOptions(unbounded).isEmpty());
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

	/**
	 * A constant holding a value of arbitrary type reports that value as its constant value,
	 * so equal values hash alike and distinct values are distinguished by their hash.
	 */
	@Test(timeout = 30000)
	public void constantValueReportsItsOwnValue() {
		Assert.assertEquals(Short.valueOf((short) 7), Constant.of((short) 7).getValue());
		Assert.assertEquals(Constant.of(1.5f).hashCode(), Constant.of(1.5f).hashCode());
		Assert.assertNotEquals(Constant.of(1.5f).hashCode(), Constant.of(2.5f).hashCode());
	}

	/**
	 * An n-ary expression reports the operator joining its operands, and its result type is
	 * promoted from the operand types: any floating-point operand yields a double, any wider
	 * integral operand a long, and integer operands alone an integer.
	 */
	@Test(timeout = 30000)
	public void naryExpressionsReportOperatorAndPromotedType() {
		Expression<Long> wide = new StaticReference<>(Long.class, "w");
		Expression<Double> fp = new StaticReference<>(Double.class, "f");

		Assert.assertEquals("+", ((Sum<?>) Sum.of(ref("x"), ref("y"))).getOperator());
		Assert.assertEquals("*", ((Product<?>) Product.of(ref("x"), ref("y"))).getOperator());
		Assert.assertEquals(Integer.class, Sum.of(ref("x"), ref("y")).getType());
		Assert.assertEquals(Long.class, Sum.of(ref("x"), wide).getType());
		Assert.assertEquals(Double.class, Product.of(ref("x"), wide, fp).getType());
	}

	/**
	 * The bounds of a difference pair the minuend's bound with the opposite bound of each
	 * subtrahend: subtracting a value in {@code [0, 5]} from one in {@code [0, 3]} can reach
	 * as high as {@code 3} and as low as {@code -5}.
	 */
	@Test(timeout = 30000)
	public void differenceBoundsUseOppositeSubtrahendBounds() {
		Expression<Integer> small = kernel().imod(4);
		Expression<Integer> large = kernel().imod(6);
		Difference<Integer> diff = new Difference<>(small, large);

		Assert.assertEquals(3, diff.upperBound(null).orElseThrow());
		Assert.assertEquals(-5, diff.lowerBound(null).orElseThrow());

		Difference<Integer> offset = new Difference<>(e(10), large);
		Assert.assertEquals(10, offset.upperBound(null).orElseThrow());
		Assert.assertEquals(5, offset.lowerBound(null).orElseThrow());
		Assert.assertTrue(new Difference<>(small, ref("x")).upperBound(null).isEmpty());
	}

	/**
	 * A product with a masked factor is the mask's guard applied to the product of the
	 * masked value, and a product of an arithmetic generator with a constant is absorbed
	 * into the generator's scale.
	 */
	@Test(timeout = 30000)
	public void productDelegatesToMaskAndGenerator() {
		Expression<Boolean> guard = kernel().lessThan(e(3));
		Expression<?> masked = Product.of(Mask.of(guard, ref("x")), ref("y"));

		Assert.assertTrue(masked instanceof Mask);
		Assert.assertEquals(guard, ((Mask<?>) masked).getMask());
		Assert.assertEquals(Product.of(ref("x"), ref("y")), ((Mask<?>) masked).getMaskedValue());

		Expression<?> scaled = Product.of(ArithmeticGenerator.create(kernel(), 2, 3, 12), e(5));
		Assert.assertTrue(scaled instanceof ArithmeticGenerator);
		Assert.assertEquals(10, ((ArithmeticGenerator<?>) scaled).getScale());
		Assert.assertEquals(3, ((ArithmeticGenerator<?>) scaled).getGranularity());
		Assert.assertEquals(12, ((ArithmeticGenerator<?>) scaled).getMod());
	}

	/**
	 * A modulus of a modulus collapses when the two divisors are multiples of one another:
	 * an outer divisor equal to, or a multiple of, the inner one leaves the inner modulus
	 * unchanged, and an inner divisor that is a multiple of the outer one is dropped.
	 * Unrelated divisors keep both levels.
	 */
	@Test(timeout = 30000)
	public void nestedModulusReducesRelatedDivisors() {
		Expression<?> x = ref("x");
		Expression<?> inner = Mod.of(x, e(3), false);

		Assert.assertEquals(inner, Mod.of(inner, e(3), false));
		Assert.assertEquals(inner, Mod.of(inner, e(6), false));
		Assert.assertEquals(Mod.of(x, e(3), false), Mod.of(Mod.of(x, e(12), false), e(3), false));

		Expression<?> unrelated = Mod.of(Mod.of(x, e(5), false), e(3), false);
		Assert.assertTrue(unrelated instanceof Mod);
		Assert.assertEquals(Mod.of(x, e(5), false), unrelated.getChildren().get(0));
	}

	/**
	 * A zero divisor on either level of a nested modulus is left unreduced rather than
	 * throwing an {@link ArithmeticException} while the expression is being simplified.
	 */
	@Test(timeout = 30000)
	public void nestedModulusWithZeroDivisorIsNotReduced() {
		Expression<?> x = ref("x");

		Expression<?> innerZero = Mod.of(x, e(0), false);
		Expression<?> outer = Mod.of(innerZero, e(3), false);
		Assert.assertTrue(outer instanceof Mod);
		Assert.assertEquals(innerZero, outer.getChildren().get(0));
		Assert.assertEquals(3, outer.getChildren().get(1).longValue().orElseThrow());

		Expression<?> inner = Mod.of(x, e(3), false);
		Expression<?> outerZero = Mod.of(inner, e(0), false);
		Assert.assertTrue(outerZero instanceof Mod);
		Assert.assertEquals(inner, outerZero.getChildren().get(0));
		Assert.assertEquals(0, outerZero.getChildren().get(1).longValue().orElseThrow());
	}

	/**
	 * {@code k * x + (x % k)} is recognised in either operand order, and its modulo and
	 * quotient rewrites follow the identities the shape admits; a remainder whose divisor
	 * differs from the scale, or whose dividend differs from the scaled operand, is not
	 * recognised.
	 */
	@Test(timeout = 30000)
	public void sumRecognisesScaledRemainder() {
		Expression<?> x = ref("x");
		Sum<?> scaled = sum(Product.of(e(3), x), Mod.of(x, e(3), false));
		Sum<?> reversed = sum(Mod.of(x, e(3), false), Product.of(x, e(3)));

		Assert.assertTrue(scaled.scaledRemainder().isPresent());
		Assert.assertTrue(reversed.scaledRemainder().isPresent());

		Sum.ScaledRemainder remainder = scaled.scaledRemainder().orElseThrow();
		Assert.assertEquals(Mod.of(x, e(3), false), remainder.modulo(3));
		Assert.assertEquals(Product.of(Mod.of(x, e(3), false), e(4)), remainder.modulo(9));
		Assert.assertNull(remainder.modulo(6));
		Assert.assertNull(remainder.modulo(4));
		Assert.assertNull(remainder.modulo(2));

		Assert.assertEquals(Quotient.of(x, e(2)), remainder.quotient(6));
		Assert.assertNull(remainder.quotient(4));
		Assert.assertNull(remainder.quotient(2));

		Assert.assertTrue(sum(Product.of(e(3), x), Mod.of(x, e(5), false))
				.scaledRemainder().isEmpty());
		Assert.assertTrue(sum(Product.of(e(3), x), Mod.of(ref("y"), e(3), false))
				.scaledRemainder().isEmpty());
		Assert.assertTrue(sum(Product.of(e(3), x), ref("y"))
				.scaledRemainder().isEmpty());
	}
}
