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
import io.almostrealism.lang.LanguageOperations;
import org.almostrealism.c.CJNILanguageOperations;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.almostrealism.hardware.metal.MetalLanguageOperations;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the integer literals the target language backends render into generated source.
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
}
