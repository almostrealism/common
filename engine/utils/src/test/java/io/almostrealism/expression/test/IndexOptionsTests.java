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

import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Comparison;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Checks that {@link Expression#getIndexOptions(Index)}, which describes the domain of an
 * index as an {@link IndexSequence}, answers with exactly the values the enumerated
 * {@code Set<Integer>} analysis produced, and that {@link Comparison#simplify} makes the
 * same decisions from it.
 *
 * <p>{@link #enumeratedOptions(Expression, Index)} is a direct transcription of that
 * enumerated analysis (the index, {@link Mod}, {@link Quotient}, {@link Sum} and the
 * general union rules), kept here as the reference the sequence domains are compared
 * against, including the integer overflow and non-positive divisor cases it handled in
 * its own way.</p>
 */
public class IndexOptionsTests extends TestSuiteBase {

	/**
	 * Index ranges, with and without a limit, against the index itself and another index.
	 */
	@Test(timeout = 30000)
	public void rangeDomainsMatchEnumeration() {
		KernelIndex k = kernel();

		for (int limit : new int[] { 1, 2, 7, 20, 100, ScopeSettings.indexOptionLimit }) {
			assertSameOptions(k.withLimit(limit), k);
			assertSameOptions(new DefaultIndex("i", limit), new DefaultIndex("i", limit));
			assertSameOptions(new DefaultIndex("i", limit), new DefaultIndex("j", limit));
		}

		assertSameOptions(k.withLimit(ScopeSettings.indexOptionLimit + 1), k);
		assertSameOptions(new DefaultIndex("free"), new DefaultIndex("free"));
	}

	/**
	 * An index with a zero or negative limit admits no values, so its domain is empty
	 * rather than undetermined, as the enumerated analysis found.
	 */
	@Test(timeout = 30000)
	public void nonPositiveLimitIsEmptyDomain() {
		for (long limit : new long[] { 0, -1, -50 }) {
			DefaultIndex i = new DefaultIndex("i", limit);
			assertSameOptions(i, i);

			Optional<IndexSequence> options = i.getIndexOptions(i);
			Assert.assertTrue(options.isPresent());
			Assert.assertTrue(options.get().isEmpty());
			Assert.assertEquals(0, options.get().distinctCount());
		}
	}

	/**
	 * Remainders of an index, including a modulus beyond the option limit, a modulus that
	 * does not fit an {@code int} and a negative modulus.
	 */
	@Test(timeout = 30000)
	public void modDomainsMatchEnumeration() {
		DefaultIndex free = new DefaultIndex("i");
		KernelIndex k = kernel().withLimit(1000);

		for (long m : new long[] { 2, 3, 20, 999, ScopeSettings.indexOptionLimit,
				ScopeSettings.indexOptionLimit + 1L, (1L << 32) + 5, -3 }) {
			assertSameOptions(free.imod(m), free);
			assertSameOptions(k.imod(m), k);
		}

		Expression<?> overflow = free.imod((1L << 32) + 5);
		Assert.assertTrue(overflow instanceof Mod);
		Assert.assertEquals(5, overflow.getIndexOptions(free).orElseThrow().distinctCount());

		Expression<?> negative = free.imod(-3);
		Assert.assertTrue(negative instanceof Mod);
		Assert.assertTrue(negative.getIndexOptions(free).orElseThrow().isEmpty());
	}

	/**
	 * Quotients of an index, including a divisor that does not divide the limit, a
	 * negative divisor, and multiples that leave the {@code int} range.
	 */
	@Test(timeout = 30000)
	public void quotientDomainsMatchEnumeration() {
		for (long limit : new long[] { 6, 7, 100, 1000 }) {
			DefaultIndex i = new DefaultIndex("i", limit);

			for (long d : new long[] { 2, 3, 4, 20, 99, -3, -100 }) {
				assertSameOptions(quotient(i, d), i);
			}
		}

		DefaultIndex large = new DefaultIndex("i", 1L << 40);
		Expression<?> wrapped = quotient(large, 1L << 30);
		Assert.assertTrue(wrapped instanceof Quotient);
		assertSameOptions(wrapped, large);

		DefaultIndex small = new DefaultIndex("i", 10);
		Expression<?> negative = quotient(small, -3);
		Assert.assertTrue(negative instanceof Quotient);
		assertSameOptions(negative, small);
	}

	/**
	 * Sums, products and comparisons of index terms, where the domains of the children are
	 * combined, shared when nested, and rejected when a sum's terms are not nested.
	 */
	@Test(timeout = 30000)
	public void combinedDomainsMatchEnumeration() {
		KernelIndex k = kernel().withLimit(120);
		Expression<?> x = new StaticReference<>(Integer.class, "x");

		List<Expression<?>> expressions = List.of(
				k.imod(20).divide(5).imod(2).add(k.imod(20)),
				k.divide(4).multiply(2).add(k.imod(2)),
				k.imod(4).divide(2).multiply(4).add(k.divide(4).multiply(2)).add(k.imod(2)),
				k.imod(6).multiply(k.imod(10)),
				k.imod(3).multiply(k.imod(5)).add(k.imod(7)),
				k.divide(3).multiply(k.divide(5)),
				k.divide(3).add(k.divide(5)),
				k.add(x),
				k.imod(8).multiply(x),
				x.multiply(new IntegerConstant(3)),
				k.imod(4).lessThan(3),
				k.divide(7).multiply(k.imod(11)).greaterThan(e(30)));

		for (Expression<?> e : expressions) {
			assertSameOptions(e, k);
		}
	}

	/**
	 * A union whose children are nested returns the containing child's domain itself, and
	 * a union of children that are not nested holds each of their values once.
	 */
	@Test(timeout = 30000)
	public void unionSharesContainingDomain() {
		KernelIndex k = kernel().withLimit(60);
		IndexSequence range = k.getIndexOptions(k).orElseThrow();

		Assert.assertSame(range, range.union(k.imod(5).getIndexOptions(k).orElseThrow()));
		Assert.assertSame(range, k.imod(5).getIndexOptions(k).orElseThrow().union(range));

		IndexSequence threes = quotient(k, 3).getIndexOptions(k).orElseThrow();
		IndexSequence fives = quotient(k, 5).getIndexOptions(k).orElseThrow();
		IndexSequence union = threes.union(fives);
		Assert.assertEquals(28, union.distinctCount());
		Assert.assertTrue(union.containsAll(threes));
		Assert.assertTrue(union.containsAll(fives));
		Assert.assertFalse(union.contains(7));
	}

	/**
	 * Comparisons over the kernel index fold to a constant exactly when the enumerated
	 * analysis folded them, keep their form otherwise, and agree with the unsimplified
	 * comparison at every kernel position.
	 */
	@Test(timeout = 30000)
	public void comparisonSimplifyMatchesEnumeration() {
		int count = 64;
		NoOpKernelStructureContext context = new NoOpKernelStructureContext(count);
		KernelIndex k = kernel(context);
		Expression<?> x = new StaticReference<>(Integer.class, "x");

		List<Expression<Boolean>> comparisons = List.of(
				k.imod(4).multiply(3).lessThan(12),
				k.imod(4).multiply(3).lessThan(9),
				k.divide(8).add(k.imod(8)).lessThan(e(15)),
				k.divide(8).add(k.imod(8)).lessThan(e(14)),
				k.imod(6).multiply(k.imod(6)).greaterThanOrEqual(0),
				k.imod(6).multiply(k.imod(6)).greaterThan(e(20)),
				k.divide(3).multiply(2).add(k.imod(3)).lessThanOrEqual(e(200)),
				k.imod(5).multiply(x).add(e(1)).lessThan(4),
				k.imod(5).add(k.divide(5)).multiply(e(2)).lessThan(30));

		int folded = 0;

		for (Expression<Boolean> c : comparisons) {
			Optional<Boolean> expected = enumeratedFold(c, k);
			Expression<Boolean> simplified = c.simplify(context, 0);

			if (expected.isPresent()) {
				folded++;
				Assert.assertEquals(c.getExpressionSummary(), new BooleanConstant(expected.get()), simplified);
			} else {
				Assert.assertFalse(c.getExpressionSummary(), simplified instanceof BooleanConstant);
			}

			if (simplified.isValue(new IndexValues(0))) {
				for (int i = 0; i < count; i++) {
					Assert.assertEquals(c.value(new IndexValues(i)).longValue() != 0,
							simplified.value(new IndexValues(i)).longValue() != 0);
				}
			}
		}

		Assert.assertTrue("Some comparisons fold to a constant", folded > 0);
		Assert.assertTrue("Some comparisons keep their form", folded < comparisons.size());
	}

	/**
	 * Builds the integer quotient of the given index by a constant divisor.
	 */
	private Expression<?> quotient(Expression<?> numerator, long divisor) {
		return Quotient.of(numerator,
				divisor == (int) divisor ? new IntegerConstant((int) divisor) : new LongConstant(divisor));
	}

	/**
	 * Asserts that the sequence domain of the expression has exactly the values of the
	 * enumerated domain, and is present exactly when the enumerated domain is.
	 */
	private void assertSameOptions(Expression<?> e, Index index) {
		Optional<Set<Integer>> expected = enumeratedOptions(e, index);
		Optional<IndexSequence> actual = e.getIndexOptions(index);
		String summary = e.getExpressionSummary();

		Assert.assertEquals(summary, expected.isPresent(), actual.isPresent());
		if (expected.isEmpty()) return;

		Set<Integer> values = actual.get().longValues()
				.mapToObj(Math::toIntExact).collect(Collectors.toSet());
		Assert.assertEquals(summary, expected.get(), values);
		Assert.assertEquals(summary, expected.get().size(), actual.get().distinctCount());
		Assert.assertEquals(summary, expected.get().isEmpty(), actual.get().isEmpty());

		for (int v : expected.get()) {
			Assert.assertTrue(summary, actual.get().contains(v));
		}
	}

	/**
	 * The decision the enumerated analysis made for a comparison that is already in
	 * simplified form: the constant it folded to, or empty if it left the comparison.
	 */
	private Optional<Boolean> enumeratedFold(Expression<Boolean> c, Index kernel) {
		if (c.countNodes() <= 3) return Optional.empty();

		Optional<Set<Integer>> options = enumeratedOptions(c, kernel);
		if (options.isEmpty() || options.get().isEmpty() ||
				options.get().size() > Comparison.indexOptionLimit ||
				!c.isValue(new IndexValues(0))) {
			return Optional.empty();
		}

		List<Boolean> distinct = options.get().stream().sorted()
				.map(i -> c.value(new IndexValues(i)).longValue() != 0)
				.distinct().collect(Collectors.toList());
		return distinct.size() == 1 ? Optional.of(distinct.get(0)) : Optional.empty();
	}

	/**
	 * The enumerated index-option analysis, as a set of boxed values per node.
	 */
	private static Optional<Set<Integer>> enumeratedOptions(Expression<?> e, Index index) {
		if (e instanceof Index) {
			if (!Objects.equals(e, index)) return Optional.of(Collections.emptySet());

			OptionalLong limit = ((Index) e).getLimit();
			if (limit.isEmpty() || limit.getAsLong() > ScopeSettings.indexOptionLimit) {
				return Optional.empty();
			}

			return Optional.of(IntStream.range(0, Math.toIntExact(limit.getAsLong()))
					.boxed().collect(Collectors.toSet()));
		} else if (e instanceof Mod) {
			Mod<?> mod = (Mod<?>) e;
			int m = (int) mod.getRight().longValue().orElse(Integer.MAX_VALUE);

			if (m <= ScopeSettings.indexOptionLimit && mod.getLeft().equals(index)) {
				return Optional.of(IntStream.range(0, m).boxed().collect(Collectors.toSet()));
			}
		} else if (e instanceof Quotient) {
			Quotient<?> q = (Quotient<?>) e;
			OptionalLong n = q.getNumerator().getLimit();
			OptionalLong d = q.getDenominator().longValue();

			if (!(q.isFP() || n.isEmpty() || d.isEmpty() ||
					d.getAsLong() > Integer.MAX_VALUE ||
					n.getAsLong() / d.getAsLong() > ScopeSettings.indexOptionLimit ||
					!q.getNumerator().equals(index))) {
				int di = Math.toIntExact(d.getAsLong());
				int limit = Math.toIntExact(n.getAsLong() / di) +
						(n.getAsLong() % di == 0 ? 0 : 1);
				return Optional.of(IntStream.range(0, limit)
						.map(i -> i * di).boxed().collect(Collectors.toSet()));
			}
		} else if (e instanceof Sum) {
			List<Set<Integer>> optionValues = new ArrayList<>();

			for (Expression<?> child : e.getChildren()) {
				Optional<Set<Integer>> o = enumeratedOptions(child, index);
				if (o.isEmpty()) return Optional.empty();
				if (!o.get().isEmpty()) optionValues.add(o.get());
			}

			if (optionValues.isEmpty()) return Optional.of(Collections.emptySet());

			Set<Integer> largest = Collections.max(optionValues, Comparator.comparingInt(Set::size));
			boolean allContained = optionValues.stream().allMatch(largest::containsAll);
			return allContained ? Optional.of(largest) : Optional.empty();
		}

		Set<Integer> options = new HashSet<>();

		for (Expression<?> child : e.getChildren()) {
			Optional<Set<Integer>> o = enumeratedOptions(child, index);

			if (o.isPresent() && options.size() < ScopeSettings.indexOptionLimit) {
				options.addAll(o.get());
			} else {
				return Optional.empty();
			}
		}

		return options.size() < ScopeSettings.indexOptionLimit ? Optional.of(options) : Optional.empty();
	}
}
