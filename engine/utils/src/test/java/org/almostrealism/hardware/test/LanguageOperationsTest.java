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

import io.almostrealism.code.AtomicAccumulation;
import io.almostrealism.code.NumberSystem;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Greater;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Less;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.c.CJNILanguageOperations;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.almostrealism.hardware.cuda.CudaLanguageOperations;
import org.almostrealism.hardware.js.JavaScriptLanguageOperations;
import org.almostrealism.hardware.metal.MetalLanguageOperations;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the integer and boolean literals and the comparison operators the target language
 * backends render into generated source.
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
	 * Every backend renders ordering comparisons with the C-family infix operators by
	 * default, and a {@link Greater} or {@link Less} renders whatever its language's
	 * {@link LanguageOperations#greaterThan} or {@link LanguageOperations#lessThan} returns,
	 * so a backend can replace the operators.
	 */
	@Test(timeout = 30000)
	public void comparisonsRenderThroughTheLanguage() {
		Expression<Integer> a = new StaticReference<>(Integer.class, "a");
		Expression<Integer> b = new StaticReference<>(Integer.class, "b");

		for (LanguageOperations lang : backends(Precision.FP32)) {
			String context = lang.getClass().getSimpleName();
			Assert.assertEquals(context, "a > b", Greater.of(a, b).getExpression(lang));
			Assert.assertEquals(context, "a >= b", Greater.of(a, b, true).getExpression(lang));
			Assert.assertEquals(context, "a < b", Less.of(a, b).getExpression(lang));
			Assert.assertEquals(context, "a <= b", Less.of(a, b, true).getExpression(lang));
		}

		LanguageOperations custom = new CLanguageOperations(Precision.FP32, false, false) {
			@Override
			public String greaterThan(String left, String right, boolean orEqual) {
				return (orEqual ? "ge(" : "gt(") + left + ", " + right + ")";
			}

			@Override
			public String lessThan(String left, String right, boolean orEqual) {
				return (orEqual ? "le(" : "lt(") + left + ", " + right + ")";
			}
		};

		Assert.assertEquals("gt(a, b)", Greater.of(a, b).getExpression(custom));
		Assert.assertEquals("ge(a, b)", Greater.of(a, b, true).getExpression(custom));
		Assert.assertEquals("lt(a, b)", Less.of(a, b).getExpression(custom));
		Assert.assertEquals("le(a, b)", Less.of(a, b, true).getExpression(custom));
	}

	/**
	 * Each backend renders an atomic accumulation with the indivisible update its language
	 * provides, never as a plain read-modify-write: a native atomic add on Metal and CUDA, and a
	 * compare-and-swap loop over the bits of the element on OpenCL and in native C, with an
	 * integer view as wide as the element at both single and double precision.
	 */
	@Test(timeout = 30000)
	public void atomicAccumulationRendersAnAtomicUpdate() {
		String metal = new MetalLanguageOperations(Precision.FP32).atomicAccumulation("out[i]", "v");
		Assert.assertEquals("metal::atomic_fetch_add_explicit((device metal::atomic_float *) &(out[i]), v, "
				+ "metal::memory_order_relaxed)", metal);

		Assert.assertEquals("atomicAdd(&(out[i]), v)",
				new CudaLanguageOperations(Precision.FP32).atomicAccumulation("out[i]", "v"));

		String cl32 = new OpenCLLanguageOperations(Precision.FP32).atomicAccumulation("out[i]", "v");
		Assert.assertTrue(cl32, cl32.contains("atomic_cmpxchg(ar_atomic_p") && cl32.contains("__global uint *"));
		String cl64 = new OpenCLLanguageOperations(Precision.FP64).atomicAccumulation("out[i]", "v");
		Assert.assertTrue(cl64, cl64.contains("atom_cmpxchg(ar_atomic_p") && cl64.contains("__global ulong *"));

		for (LanguageOperations lang : List.of(new CJNILanguageOperations(Precision.FP32),
				new CLanguageOperations(Precision.FP32, true, false))) {
			String c = lang.atomicAccumulation("out[i]", "v");
			Assert.assertTrue(c, c.contains("__atomic_compare_exchange_n") && c.contains("unsigned int *"));
			Assert.assertTrue(c, c.contains("&(out[i])"));
		}

		String c64 = new CJNILanguageOperations(Precision.FP64).atomicAccumulation("out[i]", "v");
		Assert.assertTrue(c64, c64.contains("unsigned long long *") && c64.contains("union { double f;"));
	}

	/**
	 * A backend that cannot express an indivisible floating-point update refuses to render one
	 * instead of rendering an update that could lose colliding additions: Metal has no 64-bit
	 * floating-point atomics, native C has none for 16-bit elements, and a language that does not
	 * define the operation at all refuses by default.
	 */
	@Test(timeout = 30000)
	public void atomicAccumulationIsRefusedWhereUnsupported() {
		assertRefusesAtomic(new MetalLanguageOperations(Precision.FP64));
		assertRefusesAtomic(new CJNILanguageOperations(Precision.FP16));
		assertRefusesAtomic(new CudaLanguageOperations(Precision.FP16));
		assertRefusesAtomic(new OpenCLLanguageOperations(Precision.FP16));
		assertRefusesAtomic(new JavaScriptLanguageOperations());
	}

	/**
	 * CUDA renders its native {@code atomicAdd} at double precision as well as single.
	 */
	@Test(timeout = 30000)
	public void cudaAtomicAccumulationAtDoublePrecision() {
		Assert.assertEquals("atomicAdd(&(out[i]), v)",
				new CudaLanguageOperations(Precision.FP64).atomicAccumulation("out[i]", "v"));
	}

	/**
	 * The OpenCL compare-and-swap loop reads the element without an atomic operation only once,
	 * before the loop, and starts every retry from the value the failed swap returned, never by
	 * dereferencing the element again.
	 */
	@Test(timeout = 30000)
	public void openCLAtomicRetriesFromSwapResult() {
		for (Precision precision : List.of(Precision.FP32, Precision.FP64)) {
			String cl = new OpenCLLanguageOperations(precision).atomicAccumulation("out[i]", "v");
			String loop = cl.substring(cl.indexOf("do {"));

			Assert.assertEquals(cl, 1, cl.split("\\*ar_atomic_p;", -1).length - 1);
			Assert.assertFalse(cl, loop.contains("*ar_atomic_p"));
			Assert.assertTrue(cl, loop.contains("ar_atomic_o = ar_atomic_r;"));
			Assert.assertTrue(cl, loop.contains("ar_atomic_r = "));
			Assert.assertTrue(cl, loop.endsWith("while (ar_atomic_r != ar_atomic_o); }"));
		}
	}

	/**
	 * An atomic accumulation into a {@link Mask masked} element reference, the form an array
	 * reference takes when {@link ScopeSettings#enableInstanceReferenceMasking} is set,
	 * accumulates into the masked element itself rather than rendering the conditional; a mask of
	 * something that is not an element reference is still refused.
	 */
	@Test(timeout = 30000)
	public void atomicAccumulationIntoMaskedReference() {
		ArrayVariable<Double> out = new ArrayVariable<>(Double.class, "out", new IntegerConstant(16));
		Expression<Integer> i = new StaticReference<>(Integer.class, "i");
		Expression<Double> v = new StaticReference<>(Double.class, "v");

		boolean masking = ScopeSettings.enableInstanceReferenceMasking;
		ScopeSettings.enableInstanceReferenceMasking = true;
		Expression<Double> masked;

		try {
			masked = out.reference(i);
		} finally {
			ScopeSettings.enableInstanceReferenceMasking = masking;
		}

		Assert.assertTrue(masked.getClass().getSimpleName(), masked instanceof Mask);

		AtomicAccumulation<Double> accumulation = new AtomicAccumulation<>(masked, v);
		Assert.assertTrue(accumulation.getDestination().isVariableReference());
		Assert.assertSame(((Mask) masked).getMaskedValue(), accumulation.getDestination());

		String rendered = accumulation.getStatement(new CudaLanguageOperations(Precision.FP32));
		Assert.assertTrue(rendered, rendered.startsWith("atomicAdd(&(out["));
		Assert.assertFalse(rendered, rendered.contains("?"));

		Expression<Boolean> positive = i.greaterThanOrEqual(new IntegerConstant(0));
		try {
			new AtomicAccumulation<>(Mask.of(positive, v), v);
			Assert.fail("A mask of a non-reference should not be an accumulation destination");
		} catch (IllegalArgumentException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	/**
	 * An FP64 OpenCL program enables {@code cl_khr_int64_base_atomics}, which the 64-bit
	 * {@code atom_cmpxchg} of the FP64 atomic accumulation requires, only when its scope contains
	 * an atomic update, wherever in the scope tree that update is: a device without the extension
	 * must still compile every other FP64 program. An FP32 program needs no pragma at all.
	 */
	@Test(timeout = 30000)
	public void openCLInt64AtomicsPragmaOnlyForAtomicFp64Programs() {
		String fp64 = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n";
		String atomics = "#pragma OPENCL EXTENSION cl_khr_int64_base_atomics : enable\n";
		Assert.assertEquals(fp64, CLComputeContext.FP64_PRAGMA);
		Assert.assertEquals(atomics, CLComputeContext.INT64_ATOMICS_PRAGMA);

		ArrayVariable<Double> out = new ArrayVariable<>(Double.class, "out", new IntegerConstant(16));
		Expression<Integer> i = new StaticReference<>(Integer.class, "i");
		Expression<Double> v = new StaticReference<>(Double.class, "v");

		Scope<Void> plain = new Scope<>("plain");
		plain.getStatements().add(out.reference(i).assign(v));
		Assert.assertFalse(plain.containsAtomicUpdate());
		Assert.assertEquals(fp64, CLComputeContext.programPragmas(plain, true));
		Assert.assertEquals("", CLComputeContext.programPragmas(plain, false));

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new AtomicAccumulation<>(out.reference(i), v));
		Assert.assertTrue(body.containsAtomicUpdate());

		Scope<Void> nested = new Scope<>("nested");
		Scope<Void> active = new Scope<>();
		active.add(body);
		nested.addCase(i.greaterThanOrEqual(new IntegerConstant(0)), active);
		Assert.assertTrue(nested.containsAtomicUpdate());
		Assert.assertEquals(fp64 + atomics, CLComputeContext.programPragmas(nested, true));
		Assert.assertEquals("", CLComputeContext.programPragmas(nested, false));

		Scope<Void> caller = new Scope<>("caller");
		caller.getRequiredScopes().add(body);
		Assert.assertTrue(caller.containsAtomicUpdate());
		Assert.assertEquals(fp64 + atomics, CLComputeContext.programPragmas(caller, true));
	}

	/**
	 * An atomic accumulation adds floating-point values, so every backend refuses an integer
	 * precision even where its width matches a floating-point one it supports.
	 */
	@Test(timeout = 30000)
	public void atomicAccumulationIsRefusedAtIntegerPrecisions() {
		for (Precision precision : List.of(NumberSystem.I32, NumberSystem.I64)) {
			assertRefusesAtomic(new CJNILanguageOperations(precision));
			assertRefusesAtomic(new CLanguageOperations(precision, false, false));
			assertRefusesAtomic(new OpenCLLanguageOperations(precision));
			assertRefusesAtomic(new CudaLanguageOperations(precision));
			assertRefusesAtomic(new MetalLanguageOperations(precision));
		}
	}

	/**
	 * The assignment target of an ordinary expression is the expression itself, and that of a
	 * {@link Mask}, including a mask of a mask, is the element it guards: the same element its
	 * assignment writes.
	 */
	@Test(timeout = 30000)
	public void assignmentTargetUnwrapsMasks() {
		ArrayVariable<Double> out = new ArrayVariable<>(Double.class, "out", new IntegerConstant(16));
		Expression<Integer> i = new StaticReference<>(Integer.class, "i");
		Expression<Double> element = out.reference(i);
		Assert.assertSame(element, element.getAssignmentTarget());

		Expression<Boolean> inside = i.lessThan(new IntegerConstant(16));
		Expression<Boolean> positive = i.greaterThanOrEqual(new IntegerConstant(0));
		Expression<Double> masked = Mask.of(inside, element);
		Expression<Double> twice = Mask.of(positive, masked);
		Assert.assertTrue(twice.getClass().getSimpleName(), twice.isMasked());
		Assert.assertTrue(twice.getChildren().get(1).isMasked());
		Assert.assertSame(element, masked.getAssignmentTarget());
		Assert.assertSame(element, twice.getAssignmentTarget());
		Assert.assertSame(element, new AtomicAccumulation<>(twice, element).getDestination());
	}

	/**
	 * Asserts that {@code lang} refuses to render an atomic accumulation.
	 *
	 * @param lang the backend to check
	 */
	private void assertRefusesAtomic(LanguageOperations lang) {
		try {
			lang.atomicAccumulation("out[i]", "v");
			Assert.fail(lang.getClass().getSimpleName() + " should refuse an atomic accumulation at "
					+ lang.getPrecision());
		} catch (UnsupportedOperationException expected) {
			Assert.assertNotNull(expected.getMessage());
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
