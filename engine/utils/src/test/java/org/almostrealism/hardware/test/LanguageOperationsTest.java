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

package org.almostrealism.hardware.test;

import io.almostrealism.code.Precision;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.lang.LanguageOperations;
import org.almostrealism.c.CJNILanguageOperations;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.almostrealism.hardware.cuda.CudaLanguageOperations;
import org.almostrealism.hardware.metal.MetalLanguageOperations;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the integer and boolean literals the target language backends render into
 * generated source.
 */
public class LanguageOperationsTest extends TestSuiteBase {

	/** A stride larger than {@link Integer#MAX_VALUE}, of the kind a backward pass produces. */
	private static final long LARGE_STRIDE = 2517630976L;

	/**
	 * Every backend, at the precision its device dictates rather than only at FP64.
	 */
	private List<LanguageOperations> backends(Precision precision) {
		return List.of(
				new CJNILanguageOperations(precision),
				new CLanguageOperations(precision, false, false),
				new OpenCLLanguageOperations(precision),
				new MetalLanguageOperations(precision));
	}

	/**
	 * Each of these languages has a 64-bit integer type, whatever floating point precision
	 * the backend runs at. The two are unrelated, and tying them together is what made a
	 * large constant unrenderable below FP64.
	 */
	@Test(timeout = 30000)
	public void everyBackendSupportsInt64() {
		for (Precision p : Precision.values()) {
			for (LanguageOperations lang : backends(p)) {
				Assert.assertTrue(lang.getClass().getSimpleName() + " " + p, lang.isInt64());
			}
		}
	}

	/**
	 * A constant outside the {@code int} range has to reach generated source intact. The
	 * generators emit 64-bit index expressions at every precision — the JNI loop variable is
	 * {@code long long}, and the OpenCL and Metal thread positions are cast to {@code long} —
	 * so a constant that shares those expressions must render rather than throw.
	 */
	@Test(timeout = 30000)
	public void largeConstantsRenderAtEveryPrecision() {
		for (Precision p : Precision.values()) {
			for (LanguageOperations lang : backends(p)) {
				String context = lang.getClass().getSimpleName() + " " + p;
				Assert.assertEquals(context,
						LARGE_STRIDE, Long.parseLong(lang.stringForLong(LARGE_STRIDE)));
				Assert.assertEquals(context,
						-LARGE_STRIDE, Long.parseLong(lang.stringForLong(-LARGE_STRIDE)));
			}
		}
	}

	/**
	 * Values that do fit are unaffected, including the boundaries.
	 */
	@Test(timeout = 30000)
	public void ordinaryConstantsAreUnchanged() {
		for (Precision p : Precision.values()) {
			for (LanguageOperations lang : backends(p)) {
				String context = lang.getClass().getSimpleName() + " " + p;
				Assert.assertEquals(context, "0", lang.stringForLong(0));
				Assert.assertEquals(context, "36", lang.stringForLong(36));
				Assert.assertEquals(context, String.valueOf(Integer.MAX_VALUE),
						lang.stringForLong(Integer.MAX_VALUE));
				Assert.assertEquals(context, String.valueOf(Integer.MIN_VALUE),
						lang.stringForLong(Integer.MIN_VALUE));
			}
		}
	}

	/**
	 * A boolean literal is written in the type each backend declares for booleans: the
	 * integer {@code 1} or {@code 0} where that type is {@code int}, since C has no
	 * {@code true} or {@code false} without {@code <stdbool.h>}, and {@code true} or
	 * {@code false} where the language has a {@code bool} of its own. A
	 * {@link BooleanConstant} renders exactly that literal.
	 */
	@Test(timeout = 30000)
	public void booleanLiteralsMatchEachBackendsBooleanType() {
		for (Precision p : Precision.values()) {
			assertBooleanLiterals(new CJNILanguageOperations(p), "int", "1", "0");
			assertBooleanLiterals(new CLanguageOperations(p, false, false), "int", "1", "0");
			assertBooleanLiterals(new OpenCLLanguageOperations(p), "int", "1", "0");
			assertBooleanLiterals(new MetalLanguageOperations(p), "bool", "true", "false");
			assertBooleanLiterals(new CudaLanguageOperations(p), "bool", "true", "false");
		}
	}

	/**
	 * Asserts that {@code lang} declares booleans as {@code type}, and that both its own
	 * boolean literals and the {@link BooleanConstant}s it renders are {@code t} and
	 * {@code f}.
	 *
	 * @param lang the backend to check
	 * @param type the type name the backend is expected to declare booleans with
	 * @param t    the expected literal for {@code true}
	 * @param f    the expected literal for {@code false}
	 */
	private void assertBooleanLiterals(LanguageOperations lang, String type, String t, String f) {
		String context = lang.getClass().getSimpleName() + " " + lang.getPrecision();
		Assert.assertEquals(context, type, lang.nameForType(Boolean.class));
		Assert.assertEquals(context, t, lang.stringForBoolean(true));
		Assert.assertEquals(context, f, lang.stringForBoolean(false));
		Assert.assertEquals(context, t, new BooleanConstant(true).getExpression(lang));
		Assert.assertEquals(context, f, new BooleanConstant(false).getExpression(lang));
	}
}
