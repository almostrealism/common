/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.DefaultKernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * A simplification must never change the value an expression computes. These cases
 * cover an integer modulo by a <em>negative</em> constant, where {@link io.almostrealism.expression.Mod}
 * evaluates with {@link Math#floorMod(long, long)} for a possibly-negative dividend
 * and a truncating {@code %} otherwise.
 *
 * <p>The modulus reductions in {@code Mod.create} apply Euclidean periodicity
 * ({@code (a + k*m) % m == a % m}). That identity is only consistent with this
 * split evaluation when the modulus is positive: dropping a multiple of a negative
 * modulus can leave a non-negative dividend whose truncated residue has the opposite
 * sign from the floored residue the original computes. Before the {@code m > 0}
 * guard, {@code (((i - -16) % -8) + -8) % -8} folded to {@code i % -8}, changing the
 * value at every odd residue (for example {@code -7} became {@code 1} at {@code i = 1}).</p>
 */
public class ModNegativeModulusSimplifyTest extends TestSuiteBase implements ExpressionFeatures {

	/** Renders expressions to source for the failure message. */
	private final LanguageOperations lang = new LanguageOperationsStub();

	/** The minimal case the differential search reduced the defect to. */
	@Test
	public void nestedNegativeModulusPreservesValue() {
		int limit = 16;
		DefaultIndex i = new DefaultIndex("i", limit);
		Expression<?> e = i.subtract((Expression) e(-16L)).imod((Expression) e(-8L))
				.add(e(-8L)).imod((Expression) e(-8L));
		assertPreservesValue(i, limit, e);
	}

	/** A single negative modulus of a sum that carries a multiple-of-modulus term. */
	@Test
	public void negativeModulusOfSumWithMultipleTerm() {
		int limit = 12;
		DefaultIndex i = new DefaultIndex("i", limit);
		// (i - 6) % -6: the -6 term is a multiple of the modulus but must not be dropped
		Expression<?> e = i.subtract(e(6)).add(e(-6L)).imod((Expression) e(-6L));
		assertPreservesValue(i, limit, e);
	}

	/** A negative modulus by a small magnitude, over a wider index. */
	@Test
	public void negativeModulusTwoPreservesValue() {
		int limit = 20;
		DefaultIndex i = new DefaultIndex("i", limit);
		Expression<?> e = i.subtract((Expression) e(-20L)).imod((Expression) e(-2L))
				.add(e(-2L)).imod((Expression) e(-2L));
		assertPreservesValue(i, limit, e);
	}

	/**
	 * Asserts that simplifying {@code e} with a kernel structure context preserves its
	 * value at every index in {@code [0, limit)}.
	 *
	 * @param i     the index the expression varies
	 * @param limit the exclusive upper bound of the index
	 * @param e     the expression
	 */
	private void assertPreservesValue(DefaultIndex i, int limit, Expression<?> e) {
		Expression<?> s = e.getSimplified(new DefaultKernelStructureContext(limit));
		for (int x = 0; x < limit; x++) {
			IndexValues v = new IndexValues().put(i, x);
			long original = e.value(v).longValue();
			long simplified = s.value(v).longValue();
			if (original != simplified) {
				throw new AssertionError("Simplification changed the value at i=" + x
						+ "\n  original   = " + render(e) + "  -> " + original
						+ "\n  simplified = " + render(s) + "  -> " + simplified);
			}
		}
	}

	/** Renders an expression to source, or its identity when rendering fails. */
	private String render(Expression<?> e) {
		try {
			return e.getExpression(lang);
		} catch (RuntimeException ex) {
			return e.getClass().getSimpleName();
		}
	}
}
