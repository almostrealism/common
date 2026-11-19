/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.util;

import io.almostrealism.expression.Expression;
import org.almostrealism.collect.computations.DefaultRandomSource;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for Almost Realism unit tests providing automatic test depth filtering
 * via the {@link TestDepth} annotation.
 *
 * <p>Extend this class instead of implementing {@link TestFeatures} directly to get
 * automatic {@link TestDepth} annotation support without any additional boilerplate.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyTest extends AlmostRealismTest {
 *     @Test(timeout = 30000)
 *     public void basicTest() {
 *         // Runs at any depth (no annotation)
 *     }
 *
 *     @Test(timeout = 30000)
 *     @TestDepth(1)
 *     public void mediumTest() {
 *         // Only runs if AR_TEST_DEPTH >= 1
 *     }
 *
 *     @Test(timeout = 60000)
 *     @TestDepth(3)
 *     public void expensiveTest() {
 *         // Only runs if AR_TEST_DEPTH >= 3
 *     }
 * }
 * }</pre>
 *
 * <h2>Test Depth Tiers</h2>
 * <p>Each tier contains a representative mix of test types and durations.
 * Depth is not a proxy for test duration — it controls thoroughness.</p>
 * <ul>
 *   <li><b>No annotation</b>: Core coverage (~20 min total), mix of fast and slow</li>
 *   <li><b>@TestDepth(1)</b>: Broader coverage (~60 min total)</li>
 *   <li><b>@TestDepth(2)</b>: Comprehensive coverage (~2 hr total)</li>
 *   <li><b>@TestDepth(3+)</b>: Full suite (~4+ hr total)</li>
 * </ul>
 *
 * <h2>Reproducible random inputs</h2>
 * <p>Before every test method, {@link #resetRandomSeed()} seeds the one process-wide
 * {@link DefaultRandomSource}, so {@code rand}/{@code randn} inputs and layer weights that are
 * given no explicit source become identical from run to run and independent of test order. A
 * failing run can be replayed by passing the logged seed as {@code -DAR_TEST_SEED=<n>}. An
 * {@link #clearDefaultRandomSource() @After} then returns the source to unseeded, so a test's
 * seeded state never leaks to non-{@code TestSuiteBase} code sharing the JVM fork.</p>
 * <p>The reset is an {@code @Before}, so draws from a subclass field initializer (which run before
 * {@code @Before}) are not covered; such a field should take an explicit source. JUnit 4 runs the
 * superclass {@code @Before} before a subclass's, so a subclass that seeds explicitly still wins.</p>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AR_TEST_DEPTH}: Minimum depth level (default: 9)</li>
 *   <li>{@code AR_TEST_PROFILE=pipeline}: Runs all tests regardless of depth</li>
 *   <li>{@code AR_TEST_SEED}: The per-test random seed. Absent uses {@link #DEFAULT_TEST_SEED};
 *       a number pins that seed; {@code random} chooses a fresh seed for every test (logged each
 *       time) to hunt order- or seed-dependent flakes.</li>
 * </ul>
 *
 * @author Michael Murray
 * @see TestDepth
 * @see TestDepthRule
 * @see TestFeatures
 * @see DefaultRandomSource
 */
public abstract class TestSuiteBase implements TestFeatures {
	static {
		if (SystemUtils.isEnabled("AR_SIGNALWIRE_ALERTS").orElse(false)) {
			SignalWireDeliveryProvider.attachDefault();
		}
	}

	/**
	 * No-op {@link ConsoleFeatures} logger for tests that do not assert on log output.
	 * Shared by all test subclasses to avoid repeating the anonymous-class declaration.
	 */
	protected static final ConsoleFeatures SILENT = new ConsoleFeatures() {};

	/**
	 * The random seed used before each test when {@code AR_TEST_SEED} is not set. It is an
	 * arbitrary fixed constant; its only purpose is to be stable, so that unseeded random inputs
	 * are the same on every run and independent of test order.
	 */
	public static final long DEFAULT_TEST_SEED = 1234567890L;

	/** The value of the {@code AR_TEST_SEED} setting, trimmed, or empty when it is not set. */
	private static final String TEST_SEED_SETTING = SystemUtils.getProperty("AR_TEST_SEED", "").trim();

	/** Whether {@code AR_TEST_SEED=random} requests a fresh, logged seed for every test. */
	private static final boolean RANDOM_SEED_PER_TEST = "random".equalsIgnoreCase(TEST_SEED_SETTING);

	/**
	 * Classes whose fixed seed has already been logged, so the {@code randomSeed=} line is emitted
	 * once per test class rather than once per method when the seed is the stable default.
	 */
	private static final Set<String> LOGGED_SEED_CLASSES = ConcurrentHashMap.newKeySet();

	/**
	 * Rule that automatically skips tests based on {@link TestDepth} annotations.
	 */
	@Rule
	public TestDepthRule depthRule = testDepthRule();

	/**
	 * Seeds the process-wide {@link DefaultRandomSource} before every test method, so that
	 * {@code rand}/{@code randn} inputs and layer weights given no explicit source are identical
	 * from run to run and independent of the order tests run in.
	 *
	 * <p>The seed is resolved from {@code AR_TEST_SEED}: {@code random} chooses a fresh seed for
	 * every test (to hunt order- or seed-dependent flakes), a number pins that seed for the whole
	 * run, and when the setting is absent {@link #testSeed()} supplies it (by default
	 * {@link #DEFAULT_TEST_SEED}). The seed is logged as {@code randomSeed=<seed>} so a failing run
	 * can be replayed with {@code -DAR_TEST_SEED=<seed>}; it is logged once per class for the stable
	 * default and for every test under {@code AR_TEST_SEED=random}.</p>
	 */
	@Before
	public void resetRandomSeed() {
		long seed = resolveTestSeed();
		DefaultRandomSource.seed(seed);

		if (RANDOM_SEED_PER_TEST || LOGGED_SEED_CLASSES.add(getClass().getName())) {
			log("randomSeed=" + seed);
		}
	}

	/**
	 * Resolves the seed for the test about to run: the {@code AR_TEST_SEED} setting takes
	 * precedence ({@code random} for a fresh per-test seed, otherwise the parsed number), and in
	 * its absence {@link #testSeed()} decides.
	 *
	 * @return the seed to apply before the test
	 */
	private long resolveTestSeed() {
		if (RANDOM_SEED_PER_TEST) {
			return new Random().nextLong();
		}

		if (!TEST_SEED_SETTING.isEmpty()) {
			return Long.parseLong(TEST_SEED_SETTING);
		}

		return testSeed();
	}

	/**
	 * The default per-test seed, used when {@code AR_TEST_SEED} is not set. A subclass whose
	 * assertions were characterized against a particular draw may override this to pin its own
	 * seed, though passing an explicit {@link java.util.Random} to {@code rand}/{@code randn} is
	 * usually clearer.
	 *
	 * @return the seed to apply before each test when {@code AR_TEST_SEED} is not set
	 */
	protected long testSeed() {
		return DEFAULT_TEST_SEED;
	}

	/**
	 * Returns the {@link DefaultRandomSource} to its unseeded state after each test, so a test's
	 * seeded draws do not leak to code that runs later in the same JVM fork and is not a
	 * {@link TestSuiteBase} test.
	 */
	@After
	public void clearDefaultRandomSource() {
		DefaultRandomSource.clear();
	}

	/**
	 * Removes any profile the test that just ran left assigned.
	 *
	 * <p>{@link TestFeatures#initKernelMetrics} installs a profile through
	 * {@link Hardware#assignProfile}, which keeps its listeners in static fields —
	 * among them {@link Expression#timing}, which makes every expression comparison
	 * and every expression cache probe record a timing entry. Nothing in
	 * {@code initKernelMetrics} removes them again, so the profiling one test asked
	 * for otherwise stays switched on for every test that shares the JVM after it,
	 * and its cost is charged to whichever test happens to run next.</p>
	 *
	 * <p>The listeners are installed and removed as a set, so any one of them answers
	 * whether a profile is assigned. {@link Expression#timing} is the one to ask
	 * because reading it does not load {@link Hardware}, and so does not initialize a
	 * backend on behalf of a test that never used one.</p>
	 *
	 * <p>JUnit runs a subclass's {@code @After} methods before the superclass's, so a
	 * test that saves its profile on the way out still finds it assigned.</p>
	 */
	@After
	public void clearProfile() {
		if (Expression.timing != null) {
			Hardware.getLocalHardware().clearProfile();
		}
	}
}
