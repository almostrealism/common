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

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.TraversableRepeatedProducerComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.io.Console;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Primary testing interface providing comprehensive utilities for unit testing, kernel testing,
 * and hardware performance validation in the Almost Realism framework.
 *
 * <p>This interface extends {@link CodeFeatures}, {@link TensorTestFeatures}, and {@link TestSettings}
 * to provide a unified API for:</p>
 * <ul>
 *   <li><b>Assertions</b> - Standard assertions ({@link #assertTrue}, {@link #assertEquals}, etc.)
 *       with support for {@link PackedCollection} comparisons</li>
 *   <li><b>Collection Comparison</b> - Methods like {@link #assertSimilar} for tolerance-based
 *       floating-point comparisons accounting for hardware precision</li>
 *   <li><b>Kernel Testing</b> - {@link #kernelTest} for validating kernel operations across
 *       multiple execution modes (evaluation, operation, optimized)</li>
 *   <li><b>Hardware Metrics</b> - {@link #initKernelMetrics} and {@link #logKernelMetrics} for
 *       profiling kernel execution and performance analysis</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyTest implements TestFeatures {
 *     @Test
 *     public void testOperation() {
 *         // Initialize hardware profiling
 *         initKernelMetrics();
 *
 *         // Create operation
 *         Producer<PackedCollection> op = createOperation();
 *
 *         // Run kernel test with validation
 *         kernelTest(() -> op, result -> {
 *             assertEquals(expectedValue, result.toDouble());
 *         });
 *
 *         // Log performance metrics
 *         logKernelMetrics();
 *     }
 * }
 * }</pre>
 *
 * <h2>Environment Setup</h2>
 * <p>Tests using this interface require hardware acceleration environment variables:</p>
 * <pre>
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=native
 * </pre>
 *
 * @author Michael Murray
 * @see TestSettings for test configuration options
 * @see ModelTestFeatures for ML-specific testing utilities
 * @see TensorTestFeatures for tensor creation utilities
 */
public interface TestFeatures extends CodeFeatures, TensorTestFeatures, TestSettings {
	Console console = Console.root.child();

	/**
	 * Prints a {@link PackedCollection} as a formatted grid with the specified dimensions.
	 * If the collection is larger than the grid size, only the first portion is displayed.
	 *
	 * @param rows     the number of rows to display
	 * @param colWidth the number of columns per row
	 * @param value    the collection to print
	 */
	default void print(int rows, int colWidth, PackedCollection value) {
		if (value.getShape().getTotalSize() > (rows * colWidth)) {
			value = value.range(shape(rows * colWidth), 0);
		}

		value.reshape(shape(rows, colWidth).traverse()).print();
		System.out.println("--");
	}

	/**
	 * Returns a string describing the index options for an expression, useful for debugging
	 * kernel traversal behavior.
	 *
	 * @param e the expression to describe
	 * @return a string containing the expression and its index options
	 */
	default String describeOptions(Expression<?> e) {
		Set<Integer> options = e.getIndexOptions(kernel()).orElseThrow();
		return e.getExpression(Expression.defaultLanguage()) + " | " + options;
	}

	/**
	 * Asserts that the specified object is not null.
	 *
	 * @param o the object to check
	 * @throws NullPointerException if the object is null
	 */
	default void assertNotNull(Object o) {
		if (o == null) {
			throw new NullPointerException();
		}
	}

	/**
	 * Asserts that the specified object is not null, with a custom error message.
	 *
	 * @param msg the message to display if the assertion fails
	 * @param o   the object to check
	 * @throws NullPointerException if the object is null
	 */
	default void assertNotNull(String msg, Object o) {
		if (o == null) {
			throw new NullPointerException(msg);
		}
	}

	/**
	 * Asserts that the specified condition is true.
	 *
	 * @param condition the condition to verify
	 * @throws AssertionError if the condition is false
	 */
	default void assertTrue(boolean condition) {
		assertTrue(null, condition);
	}

	/**
	 * Asserts that the specified condition is true, with a custom error message.
	 *
	 * @param msg       the message to display if the assertion fails
	 * @param condition the condition to verify
	 * @throws AssertionError if the condition is false
	 */
	default void assertTrue(String msg, boolean condition) {
		if (!condition) {
			throw new AssertionError(msg);
		}
	}

	/**
	 * Asserts that the specified condition is false, with a custom error message.
	 *
	 * @param msg       the message to display if the assertion fails
	 * @param condition the condition to verify is false
	 * @throws AssertionError if the condition is true
	 */
	default void assertFalse(String msg, boolean condition) {
		assertTrue(msg, !condition);
	}

	/**
	 * Asserts that the specified condition is false.
	 *
	 * @param condition the condition to verify is false
	 * @throws AssertionError if the condition is true
	 */
	default void assertFalse(boolean condition) {
		assertTrue(!condition);
	}

	/**
	 * Asserts that two objects are not equal using {@link Objects#equals}.
	 *
	 * @param expected the first value
	 * @param actual   the second value that should not equal the first
	 * @throws AssertionError if the objects are equal
	 */
	default void assertNotEquals(Object expected, Object actual) {
		if (Objects.equals(expected, actual)) {
			throw new AssertionError(actual + " == " + expected);
		}
	}

	/**
	 * Asserts that two objects are equal using {@link Objects#equals}.
	 * Supports {@link PackedCollection} and {@link Number} comparisons.
	 *
	 * @param expected the expected value
	 * @param actual   the actual value to compare
	 * @throws AssertionError if the objects are not equal
	 */
	default void assertEquals(Object expected, Object actual) {
		if (actual instanceof PackedCollection) {
			assertEquals(expected, (PackedCollection) actual);
		} else if (!Objects.equals(expected, actual)) {
			throw new AssertionError(actual + " != " + expected);
		}
	}

	/**
	 * Asserts that an expected value equals a {@link PackedCollection} value.
	 * Supports {@link Number} and {@link PackedCollection} expected types.
	 *
	 * @param expected the expected value (Number or PackedCollection)
	 * @param actual   the actual collection value
	 * @throws AssertionError if the values are not equal
	 */
	default void assertEquals(Object expected, PackedCollection actual) {
		if (expected instanceof Number) {
			assertEquals(((Number) expected).doubleValue(), actual.toDouble());
		} else if (expected instanceof PackedCollection) {
			assertEquals((PackedCollection) expected, actual);
		} else if (!Objects.equals(expected, actual)) {
			throw new AssertionError(actual + " != " + expected);
		}
	}

	/**
	 * Asserts that two {@link PackedCollection} instances are equal, with a custom error message.
	 *
	 * @param msg      the message to display if the assertion fails
	 * @param expected the expected collection
	 * @param actual   the actual collection to compare
	 * @throws AssertionError if the collections are not equal
	 */
	default void assertEquals(String msg, PackedCollection expected, PackedCollection actual) {
		try {
			assertEquals(expected, actual);
		} catch (AssertionError e) {
			if (msg != null) {
				throw new AssertionError(msg, e);
			}

			throw e;
		}
	}

	/**
	 * Computes the average absolute difference between two {@link PackedCollection} instances.
	 * Useful for measuring how similar two collections are without triggering an assertion failure.
	 *
	 * @param expected the expected collection values
	 * @param actual   the actual collection values
	 * @return the average absolute difference between corresponding elements
	 */
	default double compare(PackedCollection expected, PackedCollection actual) {
		double exp[] = expected.toArray();
		double act[] = actual.toArray();
		return IntStream.range(0, exp.length)
				.mapToDouble(i -> Math.abs(exp[i] - act[i]))
				.average().orElseThrow();
	}

	/**
	 * Asserts that two {@link PackedCollection} instances are equal.
	 * Compares shapes (ignoring axis differences) and element values.
	 *
	 * @param expected the expected collection
	 * @param actual   the actual collection to compare
	 * @throws AssertionError if shapes differ or any element values differ
	 */
	default void assertEquals(PackedCollection expected, PackedCollection actual) {
		if (!expected.getShape().equalsIgnoreAxis(actual.getShape())) {
			throw new AssertionError(actual.getShape() + " != " + expected.getShape());
		}

		double[] ev = expected.toArray();
		double[] ov = actual.toArray();
		assertEquals(ev.length, ov.length);

		for (int i = 0; i < ev.length; i++) {
			assertEquals(ev[i], ov[i]);
		}
	}

	/**
	 * Asserts that two double values are equal within hardware precision tolerance.
	 * The tolerance is determined by the local hardware's precision epsilon.
	 *
	 * @param a the expected value
	 * @param b the actual value
	 * @throws AssertionError if the values differ beyond tolerance
	 */
	default void assertEquals(double a, double b) {
		assertEquals(a, b, true);
	}

	/**
	 * Asserts that two double values are equal within hardware precision tolerance,
	 * with a custom error message.
	 *
	 * @param msg the message to display if the assertion fails
	 * @param a   the expected value
	 * @param b   the actual value
	 * @throws AssertionError if the values differ beyond tolerance
	 */
	default void assertEquals(String msg, double a, double b) {
		assertEquals(msg, a, b, true);
	}

	/**
	 * Asserts that two int values are equal, with a custom error message.
	 *
	 * @param msg      the message to display if the assertion fails
	 * @param expected the expected value
	 * @param actual   the actual value
	 * @throws AssertionError if the values are not equal
	 */
	default void assertEquals(String msg, int expected, int actual) {
		try {
			assertEquals(expected, actual);
		} catch (AssertionError e) {
			if (msg != null) {
				throw new AssertionError(msg, e);
			}

			throw e;
		}
	}

	/**
	 * Asserts that two int values are equal.
	 *
	 * @param expected the expected value
	 * @param actual   the actual value
	 * @throws AssertionError if the values are not equal
	 */
	default void assertEquals(int expected, int actual) {
		if (actual != expected) {
			throw new AssertionError(actual + " != " + expected);
		}
	}

	/**
	 * Asserts that two double values are not equal within hardware precision tolerance.
	 *
	 * @param a the first value
	 * @param b the second value that should differ
	 * @throws AssertionError if the values are equal within tolerance
	 */
	default void assertNotEquals(double a, double b) {
		assertEquals(a, b, false);
	}

	private static void assertEquals(double a, double b, boolean positive) {
		assertEquals(null, a, b, positive);
	}

	private static void assertEquals(String msg, double a, double b, boolean positive) {
		double gap = Math.pow(10, 3) * Hardware.getLocalHardware().getPrecision().epsilon(true);
		double fallbackGap = 10 * gap;

		if (Math.abs(a - b) >= gap) {
			if (positive) {
				if (Math.abs(a - b) >= fallbackGap) {
					System.err.println("TestFeatures: " + b + " != " + a);
					throw new AssertionError(msg == null ? b + " != " + a : msg);
				} else {
					System.out.println("TestFeatures: " + b + " != " + a);
				}
			}
		} else if (!positive) {
			System.err.println("TestFeatures: " + b + " == " + a);
			throw new AssertionError(msg == null ? b + " == " + a : msg);
		}
	}

	/**
	 * Asserts that two double values are similar within a default tolerance of 0.1% (0.001).
	 * Uses a relative comparison that accounts for the magnitude of the values.
	 *
	 * @param a the expected value
	 * @param b the actual value
	 * @throws AssertionError if the values differ by more than 0.1%
	 * @see #assertSimilar(double, double, double)
	 */
	default void assertSimilar(double a, double b) {
		assertSimilar(a, b, 0.001);
	}

	/**
	 * Asserts that two double values are similar within a specified relative tolerance.
	 * The tolerance is relative to the larger absolute value, with a minimum bound
	 * of the hardware epsilon.
	 *
	 * <p>For example, with {@code r = 0.01} (1% tolerance):</p>
	 * <ul>
	 *   <li>{@code assertSimilar(100.0, 100.5, 0.01)} passes (0.5% difference)</li>
	 *   <li>{@code assertSimilar(100.0, 102.0, 0.01)} fails (2% difference)</li>
	 * </ul>
	 *
	 * @param a the expected value
	 * @param b the actual value
	 * @param r the relative tolerance (e.g., 0.001 for 0.1% tolerance)
	 * @throws AssertionError if the values differ by more than the specified tolerance
	 */
	default void assertSimilar(double a, double b, double r) {
		double gap = Math.max(Math.abs(a), Math.abs(b));
		double eps = Hardware.getLocalHardware().epsilon();
		double comp = Math.max(eps, r * gap);

		double c = Math.abs(a - b);

		if (c >= comp) {
			double s = c / gap;
			warn(b + " != " + a + " (" + s + " > " + r + ")");
			throw new AssertionError();
		}
	}

	/**
	 * Compares two {@link CollectionProducer} instances by evaluating them and asserting
	 * their shapes and element values are equal. Logs comparison details during execution.
	 *
	 * @param expected the producer yielding the expected values
	 * @param result   the producer yielding the actual values to compare
	 * @throws AssertionError if shapes differ or any element values differ
	 */
	default void compare(CollectionProducer expected,
						 CollectionProducer result) {
		PackedCollection e = expected.evaluate();
		PackedCollection o = result.evaluate();

		if (!e.getShape().equals(o.getShape())) {
			log(o.getShape().toStringDetail() + " != " + e.getShape().toStringDetail());
			throw new AssertionError();
		}

		log(o.getShape());

		double ev[] = e.toArray();
		double ov[] = o.toArray();

		for (int i = 0; i < ev.length; i++) {
			log(ev[i] + " vs " + ov[i]);
			assertEquals(ev[i], ov[i]);
		}
	}

	/**
	 * Runs a comprehensive kernel test validating an operation across all execution modes:
	 * kernel evaluation, operation execution, and optimized operation execution.
	 *
	 * @param supply   a supplier that creates the producer to test
	 * @param validate a consumer that validates the output collection
	 * @see #kernelTest(String, Supplier, Consumer, boolean, boolean, boolean)
	 */
	default void kernelTest(Supplier<? extends Producer<PackedCollection>> supply,
							Consumer<PackedCollection> validate) {
		kernelTest(supply, validate, true, true, true);
	}

	/**
	 * Runs a named kernel test with profiling support, validating across all execution modes.
	 *
	 * @param name     the name for the profiling node (or null for no profiling)
	 * @param supply   a supplier that creates the producer to test
	 * @param validate a consumer that validates the output collection
	 * @return the operation profile node containing timing information, or null if name was null
	 */
	default OperationProfileNode kernelTest(String name,
											Supplier<? extends Producer<PackedCollection>> supply,
											Consumer<PackedCollection> validate) {
		return kernelTest(name, supply, validate, true, true, true);
	}

	/**
	 * Runs a kernel test with selective execution modes.
	 *
	 * @param supply    a supplier that creates the producer to test
	 * @param validate  a consumer that validates the output collection
	 * @param kernel    if true, tests direct kernel evaluation
	 * @param operation if true, tests operation list execution
	 * @param optimized if true, tests optimized parallel process execution
	 */
	default void kernelTest(Supplier<? extends Producer<PackedCollection>> supply,
							Consumer<PackedCollection> validate,
							boolean kernel, boolean operation, boolean optimized) {
		kernelTest(null, supply, validate, kernel, operation, optimized);
	}

	/**
	 * Runs a comprehensive kernel test with full control over execution modes and profiling.
	 *
	 * <p>This is the primary kernel testing method that validates an operation across
	 * multiple execution strategies:</p>
	 * <ul>
	 *   <li><b>Kernel mode</b>: Direct evaluation using {@code producer.get().evaluate()}</li>
	 *   <li><b>Operation mode</b>: Execution via {@link OperationList} with explicit output destination</li>
	 *   <li><b>Optimized mode</b>: Execution via optimized {@link ParallelProcess} with copy verification</li>
	 * </ul>
	 *
	 * @param name      the name for the profiling node (or null for no profiling)
	 * @param supply    a supplier that creates the producer to test (called fresh for each mode)
	 * @param validate  a consumer that validates the output collection
	 * @param kernel    if true, tests direct kernel evaluation
	 * @param operation if true, tests operation list execution
	 * @param optimized if true, tests optimized parallel process execution
	 * @return the operation profile node containing timing information, or null if name was null
	 */
	default OperationProfileNode kernelTest(String name,
							Supplier<? extends Producer<PackedCollection>> supply, Consumer<PackedCollection> validate,
							boolean kernel, boolean operation, boolean optimized) {
		OperationProfileNode profile = name == null ? null : new OperationProfileNode(name);

		AtomicReference<PackedCollection> outputRef = new AtomicReference<>();

		if (kernel) {
			System.out.println("TestFeatures: Running kernel evaluation...");
			Producer<PackedCollection> p = supply.get();
			profile(profile, () -> {
				PackedCollection output = p.get().evaluate();
				log("Output Shape = " + output.getShape() +
						" [" + output.getShape().getCountLong() + "x" + output.getShape().getSize() + "]");
				log("Validating kernel output...");
				validate.accept(output);
				outputRef.set(output);
			});
		} else {
			outputRef.set(new PackedCollection(shape(supply.get())));
		}

		if (operation) {
			outputRef.get().clear();

			PackedCollection output = outputRef.get();

			log("Running kernel operation...");
			OperationList op = new OperationList();
			op.add(output.getAtomicMemLength(), supply.get(), p(output));
			profile(profile, op);
			log("Validating kernel output...");
			validate.accept(output);
		}

		if (optimized) {
			outputRef.get().clear();

			PackedCollection output = outputRef.get();
			PackedCollection dest = new PackedCollection(output.getShape());

			log("Running optimized kernel operation...");
			OperationList op = new OperationList();
			op.add(output.getAtomicMemLength(), supply.get(), p(output));
			op.add(copy(p(output), p(dest), output.getMemLength()));

			ParallelProcess<?, Runnable> p = op.optimize();
			profile(profile, p);
			log("Validating optimized kernel output...");
			validate.accept(output);
			log("Validating optimized kernel output copy...");
			validate.accept(dest);
		}

		return profile;
	}

	/**
	 * Initializes kernel metrics collection using a default hardware operator profile.
	 * This sets up profiling for subsequent kernel operations.
	 *
	 * <p>Call this at the beginning of a test to enable performance measurement,
	 * then call {@link #logKernelMetrics()} at the end to output results.</p>
	 *
	 * @see #logKernelMetrics()
	 */
	default void initKernelMetrics() {
		initKernelMetrics(new OperationProfile(null, "HardwareOperator",
				OperationProfile.appendContext(OperationMetadata::getDisplayName)));
	}

	/**
	 * Initializes kernel metrics collection with a custom profile.
	 * Associates the profile with the local hardware for timing collection.
	 *
	 * @param <T>     the profile type
	 * @param profile the operation profile to use for metrics collection
	 * @return the same profile instance for method chaining
	 */
	default <T extends OperationProfile> T initKernelMetrics(T profile) {
		Hardware.getLocalHardware().assignProfile(profile);
		KernelTraversalProvider.clearTimes();
		return profile;
	}

	/**
	 * Converts an int array to a string representation.
	 *
	 * @param a the array to convert
	 * @return the string representation of the array
	 */
	default String s(int[] a) {
		return Arrays.toString(a);
	}

	/**
	 * Logs kernel metrics and performance statistics to the console.
	 * Outputs kernel traversal times, scope settings statistics, and cache configuration.
	 *
	 * @see #initKernelMetrics()
	 */
	default void logKernelMetrics() {
		logKernelMetrics(null);
	}

	/**
	 * Logs kernel metrics with an optional operation profile.
	 * Prints the profile summary if provided, followed by kernel traversal times,
	 * scope statistics, and cache configuration details.
	 *
	 * @param profile the operation profile to print (may be null)
	 */
	default void logKernelMetrics(OperationProfile profile) {
		if (profile != null) profile.print();

		KernelTraversalProvider.printTimes();
		ScopeSettings.printStats();
		log("KernelSeriesCache min nodes - " + KernelSeriesCache.minNodeCountMatch +
				" (match) | " + KernelSeriesCache.minNodeCountCache + " (cache)");
		log("KernelSeriesCache size = " + KernelSeriesCache.defaultMaxExpressions +
				" expressions | " + KernelSeriesCache.defaultMaxEntries + " entries | "
				+ (KernelSeriesCache.enableCache ? "on" : "off"));
		log("Expression kernelSeq cache is " + (ScopeSettings.enableKernelSeqCache ? "on" : "off"));
		log("TraversableRepeatedProducerComputation isolation count threshold = " +
				TraversableRepeatedProducerComputation.isolationCountThreshold);
	}

	/**
	 * Executes a runnable with verbose logging enabled if configured in {@link TestSettings}.
	 * When verbose logs are enabled, hardware operator logging is activated during execution.
	 *
	 * @param r the runnable to execute
	 */
	default void verboseLog(Runnable r) {
		if (TestSettings.verboseLogs) {
			HardwareOperator.verboseLog(r);
		} else {
			r.run();
		}
	}

	/**
	 * Creates a predicate that filters operations by their IDs.
	 *
	 * @param operationIds the operation IDs to match
	 * @return a predicate that returns true for operations with matching IDs
	 */
	default Predicate<Process> operationFilter(long... operationIds) {
		return operationFilter(LongStream.of(operationIds)
				.mapToObj(Long::valueOf).collect(Collectors.toSet()));
	}

	/**
	 * Creates a predicate that filters operations by a set of IDs.
	 *
	 * @param operationIds the set of operation IDs to match
	 * @return a predicate that returns true for operations with IDs in the set
	 */
	default Predicate<Process> operationFilter(Set<Long> operationIds) {
		return p -> p instanceof OperationInfo &&
				operationIds.contains(((OperationInfo) p).getMetadata().getId());
	}

	/**
	 * Creates a predicate that filters operations by class name substring or function name.
	 * Unwraps {@link ReshapeProducer} instances to find the underlying operation.
	 *
	 * @param classSubstringOrFunctionName a substring to match against the class simple name,
	 *                                     or an exact function name to match
	 * @return a predicate that returns true for matching operations
	 */
	default Predicate<Process> operationFilter(String classSubstringOrFunctionName) {
		return p -> {
			while (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer) p).getChildren().iterator().next();
			}

			return p.getClass().getSimpleName().contains(classSubstringOrFunctionName) ||
					(p instanceof OperationAdapter && ((OperationAdapter) p).getFunctionName().equals(classSubstringOrFunctionName));
		};
	}

	@Override
	default Console console() { return console; }
}
