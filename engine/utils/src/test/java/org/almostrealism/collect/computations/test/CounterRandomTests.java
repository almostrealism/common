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

package org.almostrealism.collect.computations.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.expression.test.BitwiseExpressionTests;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationSource;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CounterRandom;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jni.NativeComputeContext;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests for {@link CounterRandom}, the stateless counter-based generator whose values are
 * computed inside the compiled kernel.
 *
 * <p>Tolerances for the moment tests are five standard errors for the sample size used,
 * {@value #SAMPLES} values: for a uniform the standard error of the mean is
 * {@code sqrt(1/12 / n)} (about 2.8e-4) and of the variance {@code sqrt((1/80 - 1/144) / n)}
 * (about 7.3e-5); for a standard normal they are {@code 1 / sqrt(n)} (about 9.8e-4) and
 * {@code sqrt(2 / n)} (about 1.4e-3). A sample correlation between independent streams
 * has standard error {@code 1 / sqrt(n)}.</p>
 */
public class CounterRandomTests extends TestSuiteBase {
	/** Number of values in the statistical samples. */
	private static final int SAMPLES = 1 << 20;

	/** Seed used by the tests. */
	private static final long SEED = 42;

	/**
	 * Tolerance for the normal output of a single-precision backend against the double
	 * precision reference: single precision relative error is about 1.2e-7 and the Box-Muller
	 * transcendentals add a few ulp, so for samples of magnitude below 6 an absolute error of
	 * 1e-4 leaves more than a hundred-fold margin.
	 */
	private static final double NORMAL_TOLERANCE = 1e-4;

	/**
	 * Reference 32-bit word for an element, computed in Java.
	 *
	 * @param seed    the seed
	 * @param counter the counter
	 * @param index   the element index
	 * @return the word, in [0, 2^32)
	 */
	private static long word(long seed, long counter, long index) {
		long stream = BitwiseExpressionTests.pcg(BitwiseExpressionTests.pcg(seed) ^ counter);
		return BitwiseExpressionTests.pcg(stream ^ index);
	}

	/**
	 * Reference uniform value for an element.
	 *
	 * @param seed    the seed
	 * @param counter the counter
	 * @param index   the element index
	 * @return the uniform value in [0, 1)
	 */
	private static double uniform(long seed, long counter, long index) {
		return (word(seed, counter, index) >> 8) * CounterRandom.UNIT_SCALE;
	}

	/**
	 * Reference standard normal value for an element.
	 *
	 * @param seed    the seed
	 * @param counter the counter
	 * @param index   the element index
	 * @return the normal value
	 */
	private static double normal(long seed, long counter, long index) {
		double u1 = ((word(seed, counter, 2 * index) >> 8) + 1) * CounterRandom.UNIT_SCALE;
		double u2 = uniform(seed, counter, 2 * index + 1);
		return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
	}

	/**
	 * Evaluates a counter-based generator for the given counter value.
	 *
	 * @param normal  whether to produce normal values
	 * @param seed    the seed
	 * @param counter the counter value
	 * @param size    the number of values
	 * @return the generated values
	 */
	private double[] generate(boolean normal, long seed, double counter, int size) {
		PackedCollection c = PackedCollection.of(counter);

		CounterRandom random = normal ? counterRandn(shape(size), seed, p(c)) : counterRand(shape(size), seed, p(c));
		PackedCollection out = random.get().evaluate();
		double[] values = out.toArray();
		out.destroy();
		c.destroy();
		return values;
	}

	/**
	 * The kernel output equals the Java reference exactly for the uniform variant, which
	 * shows the integer hash is computed with well-defined 32-bit wraparound, and matches it
	 * to within precision for the normal variant.
	 */
	@Test(timeout = 120000)
	public void matchesReference() {
		int size = 4096;
		double[] u = generate(false, SEED, 7, size);
		double[] n = generate(true, SEED, 7, size);

		for (int i = 0; i < size; i++) {
			Assert.assertEquals("uniform " + i, uniform(SEED, 7, i), u[i], 0.0);
			Assert.assertEquals("normal " + i, normal(SEED, 7, i), n[i], NORMAL_TOLERANCE);
		}
	}

	/**
	 * The random values are computed in the generated kernel from the index and the
	 * counter: the compiled source contains the hash constants and integer operators, and
	 * the operation reads no input other than the counter. The profile is saved to
	 * {@code target/test-profiles/counterRandom.xml} for inspection.
	 */
	@Test(timeout = 120000)
	public void generatedInKernel() {
		PackedCollection counter = new PackedCollection(1);
		CounterRandom random = counterRandn(shape(256), SEED, p(counter));
		Assert.assertEquals(2, random.getInputs().size());

		OperationProfileNode profile = new OperationProfileNode("counterRandom");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			random.get().evaluate();
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}

		String source = profile.getOperationSources().values().stream()
				.flatMap(List::stream)
				.map(OperationSource::getSource)
				.filter(s -> s.contains("747796405"))
				.findFirst().orElse(null);
		Assert.assertNotNull("No generated source contains the hash", source);
		Assert.assertTrue(source.contains("277803737"));
		Assert.assertTrue(source.contains("4294967295"));
		Assert.assertTrue(source.contains(" ^ "));
		Assert.assertTrue(source.contains(" >> "));

		try {
			profile.save("target/test-profiles/counterRandom.xml");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		counter.destroy();
	}

	/** Uniform values lie in [0, 1) and have mean 1/2 and variance 1/12. */
	@Test(timeout = 120000)
	public void uniformMoments() {
		double[] u = generate(false, SEED, 0, SAMPLES);

		for (double v : u) {
			Assert.assertTrue(v >= 0.0 && v < 1.0);
		}

		Assert.assertEquals(0.5, mean(u), 1.5e-3);
		Assert.assertEquals(1.0 / 12, variance(u), 4e-4);
	}

	/** Normal values are finite and have mean 0 and variance 1. */
	@Test(timeout = 120000)
	public void normalMoments() {
		double[] n = generate(true, SEED, 0, SAMPLES);

		for (double v : n) {
			Assert.assertTrue(Double.isFinite(v));
		}

		Assert.assertEquals(0.0, mean(n), 5e-3);
		Assert.assertEquals(1.0, variance(n), 7e-3);
	}

	/**
	 * The same {@code (seed, counter)} reproduces the same output, including from a second
	 * computation and after another counter has been evaluated.
	 */
	@Test(timeout = 120000)
	public void reproducible() {
		double[] first = generate(false, SEED, 3, 65536);
		generate(false, SEED, 4, 65536);
		double[] second = generate(false, SEED, 3, 65536);
		Assert.assertArrayEquals(first, second, 0.0);

		double[] normalFirst = generate(true, SEED, 3, 65536);
		double[] normalSecond = generate(true, SEED, 3, 65536);
		Assert.assertArrayEquals(normalFirst, normalSecond, 0.0);
	}

	/**
	 * Advancing the counter with a compiled device-side assignment changes the output of the
	 * same compiled evaluable, so a new stream needs no recompilation, and returning to an
	 * earlier counter reproduces its stream.
	 */
	@Test(timeout = 120000)
	public void counterAdvancesWithoutRecompile() {
		int size = 1024;
		PackedCollection counter = new PackedCollection(1);
		Evaluable<PackedCollection> ev = counterRand(shape(size), SEED, p(counter)).get();
		Runnable advance = a(cp(counter), cp(counter).add(c(1.0))).get();

		double[][] streams = new double[3][];

		for (int c = 0; c < streams.length; c++) {
			if (c > 0) advance.run();
			Assert.assertEquals(c, counter.toDouble(0), 0.0);
			streams[c] = ev.evaluate().toArray();

			for (int i = 0; i < size; i++) {
				Assert.assertEquals(uniform(SEED, c, i), streams[c][i], 0.0);
			}
		}

		Assert.assertTrue(differences(streams[0], streams[1]) > size - 4);
		Assert.assertTrue(differences(streams[1], streams[2]) > size - 4);

		counter.setMem(0, 0.0);
		Assert.assertArrayEquals(streams[0], ev.evaluate().toArray(), 0.0);
		counter.destroy();
	}

	/**
	 * Outputs for consecutive counters and for distinct seeds are uncorrelated and almost
	 * never coincide position by position (two independent 24-bit uniforms are equal with
	 * probability 2^-24), and consecutive elements within one stream are uncorrelated.
	 */
	@Test(timeout = 120000)
	public void independence() {
		double[] base = generate(false, SEED, 10, SAMPLES);
		double[] nextCounter = generate(false, SEED, 11, SAMPLES);
		double[] otherSeed = generate(false, SEED + 1, 10, SAMPLES);

		double tolerance = 5.0 / Math.sqrt(SAMPLES);
		Assert.assertEquals(0.0, correlation(base, nextCounter, 0), tolerance);
		Assert.assertEquals(0.0, correlation(base, otherSeed, 0), tolerance);
		Assert.assertEquals(0.0, correlation(base, base, 1), tolerance);

		Assert.assertTrue(SAMPLES - differences(base, nextCounter) < 10);
		Assert.assertTrue(SAMPLES - differences(base, otherSeed) < 10);

		double[] normalBase = generate(true, SEED, 10, SAMPLES);
		double[] normalNext = generate(true, SEED, 11, SAMPLES);
		Assert.assertEquals(0.0, correlation(normalBase, normalNext, 0), tolerance);
		Assert.assertEquals(0.0, correlation(normalBase, normalBase, 1), tolerance);
	}

	/**
	 * The same {@code (seed, counter, shape)} evaluated on the Metal backend and on the
	 * native backend agrees: exactly for the uniform, whose integer stage is compared
	 * directly by scaling back to the 24-bit integer, and within {@link #NORMAL_TOLERANCE}
	 * for the normal. Requires both a Metal and a native compute context, which on CI means
	 * a macOS node with both drivers enabled.
	 */
	@Test(timeout = 180000)
	public void metalNativeAgreement() {
		Assume.assumeTrue("Requires a Metal compute context",
				context(ComputeRequirement.MTL, MetalComputeContext.class));
		Assume.assumeTrue("Requires a native compute context",
				context(ComputeRequirement.JNI, NativeComputeContext.class));

		int size = 65536;
		PackedCollection counter = new PackedCollection(1);
		counter.setMem(0, 5);

		for (boolean normal : new boolean[] { false, true }) {
			double[] metal = evaluate(normal, counter, size, ComputeRequirement.MTL);
			double[] cpu = evaluate(normal, counter, size, ComputeRequirement.JNI);

			for (int i = 0; i < size; i++) {
				if (normal) {
					Assert.assertEquals("normal " + i, cpu[i], metal[i], NORMAL_TOLERANCE);
				} else {
					Assert.assertTrue(metal[i] < 1.0 && cpu[i] < 1.0);
					Assert.assertEquals("word " + i, word(SEED, 5, i) >> 8, (long) (metal[i] * (1 << 24)));
					Assert.assertEquals("word " + i, word(SEED, 5, i) >> 8, (long) (cpu[i] * (1 << 24)));
					Assert.assertEquals("uniform " + i, cpu[i], metal[i], 0.0);
				}
			}
		}

		counter.destroy();
	}

	/**
	 * Evaluates the generator pinned to one backend.
	 *
	 * @param normal      whether to produce normal values
	 * @param counter     the counter collection
	 * @param size        the number of values
	 * @param requirement the backend to evaluate on
	 * @return the generated values
	 */
	private double[] evaluate(boolean normal, PackedCollection counter, int size, ComputeRequirement requirement) {
		CounterRandom random = normal ? counterRandn(shape(size), SEED, p(counter)) :
				counterRand(shape(size), SEED, p(counter));
		random.setComputeRequirements(List.of(requirement));
		return random.get().evaluate().toArray();
	}

	/**
	 * Returns whether the local hardware has a compute context of the given type.
	 *
	 * @param requirement the requirement to look up
	 * @param type        the expected context type
	 * @return {@code true} if such a context is available
	 */
	private static boolean context(ComputeRequirement requirement, Class<? extends ComputeContext> type) {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, requirement).stream()
					.anyMatch(type::isInstance);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Returns the sample mean.
	 *
	 * @param values the sample
	 * @return the mean
	 */
	private static double mean(double[] values) {
		double sum = 0;
		for (double v : values) sum += v;
		return sum / values.length;
	}

	/**
	 * Returns the sample variance.
	 *
	 * @param values the sample
	 * @return the variance
	 */
	private static double variance(double[] values) {
		double m = mean(values);
		double sum = 0;
		for (double v : values) sum += (v - m) * (v - m);
		return sum / values.length;
	}

	/**
	 * Returns the sample correlation between {@code a[i]} and {@code b[i + lag]}.
	 *
	 * @param a   the first sample
	 * @param b   the second sample
	 * @param lag the offset into the second sample
	 * @return the correlation coefficient
	 */
	private static double correlation(double[] a, double[] b, int lag) {
		int n = a.length - lag;
		double ma = 0;
		double mb = 0;

		for (int i = 0; i < n; i++) {
			ma += a[i];
			mb += b[i + lag];
		}

		ma /= n;
		mb /= n;

		double cov = 0;
		double va = 0;
		double vb = 0;

		for (int i = 0; i < n; i++) {
			double da = a[i] - ma;
			double db = b[i + lag] - mb;
			cov += da * db;
			va += da * da;
			vb += db * db;
		}

		return cov / Math.sqrt(va * vb);
	}

	/**
	 * Returns the number of positions at which the two samples differ.
	 *
	 * @param a the first sample
	 * @param b the second sample
	 * @return the number of differing positions
	 */
	private static int differences(double[] a, double[] b) {
		int count = 0;

		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) count++;
		}

		return count;
	}
}
