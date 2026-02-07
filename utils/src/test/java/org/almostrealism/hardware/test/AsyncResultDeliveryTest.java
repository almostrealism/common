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

package org.almostrealism.hardware.test;

import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Tests that demonstrate the async result delivery bug in ProcessDetailsFactory.
 *
 * <p>The bug occurs because {@code ProcessDetailsFactory.result(int index)} creates
 * a lambda that captures {@code this} and accesses {@code this.currentDetails} at
 * execution time rather than creation time. When async evaluables from different
 * {@code construct()} calls complete in overlapping manner, results get delivered
 * to the wrong {@code AcceleratedProcessDetails} instance.</p>
 *
 * <h2>Bug Pattern in ProcessDetailsFactory</h2>
 * <pre>{@code
 * protected Consumer<Object> result(int index) {
 *     // BUG: Captures 'this' and accesses 'this.currentDetails' at execution time
 *     return result -> currentDetails.result(index, result);
 * }
 * }</pre>
 *
 * <h2>Bug Scenario</h2>
 * <ol>
 *   <li>First construct() creates async evaluables with lambdas referencing currentDetails</li>
 *   <li>Sets currentDetails = details1, starts async work</li>
 *   <li>Before async completes, second construct() overwrites currentDetails = details2</li>
 *   <li>First async completes, lambda accesses currentDetails which is now details2!</li>
 *   <li>Second async completes, also delivers to details2 - DUPLICATE!</li>
 * </ol>
 *
 * @see org.almostrealism.hardware.ProcessDetailsFactory
 * @see org.almostrealism.hardware.mem.AcceleratedProcessDetails
 */
public class AsyncResultDeliveryTest implements TestFeatures {

	/**
	 * Demonstrates the core bug: a lambda that captures a mutable field by reference
	 * will access the field's current value at execution time, not creation time.
	 *
	 * <p>This is a simplified reproduction of the pattern in ProcessDetailsFactory.result().</p>
	 */
	@Test
	public void lambdaCapturesMutableFieldBug() {
		log("=== Lambda Captures Mutable Field Bug Demo ===");

		// Simulate the ProcessDetailsFactory pattern
		class SimulatedFactory {
			String currentDetails = "details1";

			// Buggy pattern: lambda captures 'this' and accesses 'this.currentDetails' at execution time
			Consumer<String> result() {
				return r -> log("Lambda delivered '" + r + "' to: " + currentDetails);
			}
		}

		SimulatedFactory factory = new SimulatedFactory();

		// Create lambda while currentDetails = "details1"
		Consumer<String> lambda1 = factory.result();
		log("Created lambda1 while currentDetails=" + factory.currentDetails);

		// Simulate second construct() - overwrites currentDetails
		factory.currentDetails = "details2";
		log("Second construct(): currentDetails now = " + factory.currentDetails);

		// When lambda1 executes, it accesses the CURRENT value (details2), not the value at creation time
		log("Executing lambda1 (was created for details1, but will see details2):");
		lambda1.accept("result_from_first_async");

		// The bug: lambda1 delivered to details2 instead of details1!
		log("\nThis demonstrates the bug: lambda1 should have delivered to details1, but delivered to details2");
	}

	/**
	 * Simulates how duplicate result errors occur when async operations overlap.
	 */
	@Test
	public void duplicateResultErrorSimulation() {
		log("\n=== Duplicate Result Error Simulation ===");

		// Simple details class that tracks results
		class SimpleDetails {
			final String name;
			String resultAt0 = null;

			SimpleDetails(String name) { this.name = name; }

			void result(int index, String value) {
				if (resultAt0 != null) {
					throw new IllegalArgumentException("Duplicate result for argument index " + index);
				}
				resultAt0 = value;
				log("Details '" + name + "' received result at index " + index + ": " + value);
			}
		}

		// Simulate the factory
		class SimulatedFactory {
			SimpleDetails currentDetails;

			Consumer<String> result(int index) {
				// BUG: captures 'this' and uses 'this.currentDetails' at execution time
				return r -> currentDetails.result(index, r);
			}
		}

		SimulatedFactory factory = new SimulatedFactory();
		AtomicReference<Throwable> error = new AtomicReference<>();

		// First construct()
		factory.currentDetails = new SimpleDetails("details1");
		Consumer<String> lambda1 = factory.result(0);
		log("First construct(): created lambda1 for details1");

		// Second construct() - OVERWRITES currentDetails!
		factory.currentDetails = new SimpleDetails("details2");
		Consumer<String> lambda2 = factory.result(0);
		log("Second construct(): created lambda2 for details2, currentDetails now points to details2");

		// First async completes - but lambda1 now delivers to details2!
		try {
			log("\nFirst async completes:");
			lambda1.accept("result_from_async1");  // Goes to details2, not details1!
		} catch (IllegalArgumentException e) {
			error.set(e);
			log("ERROR: " + e.getMessage());
		}

		// Second async completes - lambda2 also tries to deliver to details2
		try {
			log("Second async completes:");
			lambda2.accept("result_from_async2");  // DUPLICATE! details2 already has a result!
		} catch (IllegalArgumentException e) {
			error.set(e);
			log("ERROR: " + e.getMessage());
		}

		// Verify the bug
		Assert.assertNotNull("Should have caught duplicate result error", error.get());
		Assert.assertTrue("Error should be about duplicate result",
				error.get().getMessage().contains("Duplicate result"));
		log("\n*** BUG REPRODUCED: " + error.get().getMessage() + " ***");
	}

	/**
	 * Demonstrates the fix: lambda should capture the specific details instance,
	 * not the mutable field.
	 */
	@Test
	public void proposedFixWithExplicitCapture() {
		log("\n=== Proposed Fix: Explicit Instance Capture ===");

		class SimpleDetails {
			final String name;
			String resultAt0 = null;

			SimpleDetails(String name) { this.name = name; }

			void result(int index, String value) {
				if (resultAt0 != null) {
					throw new IllegalArgumentException("Duplicate result for argument index " + index);
				}
				resultAt0 = value;
				log("Details '" + name + "' received result at index " + index + ": " + value);
			}
		}

		// FIXED factory: result() captures the specific details instance
		class FixedFactory {
			// FIX: Pass targetDetails explicitly instead of using 'this.currentDetails'
			Consumer<String> result(int index, SimpleDetails targetDetails) {
				// Captures targetDetails (a parameter), not a mutable field
				return r -> targetDetails.result(index, r);
			}
		}

		FixedFactory factory = new FixedFactory();

		// First construct()
		SimpleDetails details1 = new SimpleDetails("details1");
		Consumer<String> lambda1 = factory.result(0, details1);  // Captures details1
		log("First construct(): lambda1 captures details1");

		// Second construct()
		SimpleDetails details2 = new SimpleDetails("details2");
		Consumer<String> lambda2 = factory.result(0, details2);  // Captures details2
		log("Second construct(): lambda2 captures details2");

		// Both async operations complete - results go to correct details!
		log("\nFirst async completes:");
		lambda1.accept("result1");  // Goes to details1 (correct!)

		log("Second async completes:");
		lambda2.accept("result2");  // Goes to details2 (correct!)

		// Verify fix worked
		Assert.assertEquals("details1 should have result1", "result1", details1.resultAt0);
		Assert.assertEquals("details2 should have result2", "result2", details2.resultAt0);

		log("\n*** FIX VERIFIED: Each details received its own result ***");
	}
}
