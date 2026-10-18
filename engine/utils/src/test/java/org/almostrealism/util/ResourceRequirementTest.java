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

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.internal.AssumptionViolatedException;

/**
 * Verifies the missing-resource policy of {@link TestFeatures#requireResource(String, String, boolean)}
 * and {@link TestFeatures#requireLocalResource(String, String, boolean)}: a present resource lets the
 * test proceed, a resource whose location is declared on this host fails when absent, an absent
 * resource fails on a GPU host under {@code requireResource}, and otherwise the test is skipped,
 * never passed.
 */
public class ResourceRequirementTest extends TestSuiteBase {

	/** A location property declared by the tests that need one, and cleared after each test. */
	private static final String DECLARED_PROPERTY = "AR_TEST_RESOURCE_REQUIREMENT_LOCATION";

	/** A location property that is never declared. */
	private static final String UNDECLARED_PROPERTY = "AR_TEST_RESOURCE_REQUIREMENT_UNDECLARED";

	/** Clears the declared location property. */
	@After
	public void clearDeclaration() {
		System.clearProperty(DECLARED_PROPERTY);
	}

	/** A present resource returns normally under both requirements. */
	@Test(timeout = 30_000)
	public void presentResourceProceeds() {
		requireResource("present resource", UNDECLARED_PROPERTY, true);
		requireLocalResource("present resource", UNDECLARED_PROPERTY, true);
	}

	/** An absent resource whose location is declared fails under {@code requireResource}. */
	@Test(timeout = 30_000)
	public void declaredMissingResourceFails() {
		System.setProperty(DECLARED_PROPERTY, "/nonexistent");
		Assert.assertTrue(thrownBy(() -> requireResource("declared resource", DECLARED_PROPERTY, false))
				instanceof AssertionError);
	}

	/** An absent resource whose location is declared fails under {@code requireLocalResource}. */
	@Test(timeout = 30_000)
	public void declaredMissingLocalResourceFails() {
		System.setProperty(DECLARED_PROPERTY, "/nonexistent");
		Assert.assertTrue(thrownBy(() -> requireLocalResource("declared local resource", DECLARED_PROPERTY, false))
				instanceof AssertionError);
	}

	/**
	 * An absent, undeclared resource fails under {@code requireResource} on a GPU host, and is
	 * skipped (an assumption failure, never a normal return) on a host with no GPU.
	 */
	@Test(timeout = 30_000)
	public void undeclaredMissingResourceFailsOnlyWithGpu() {
		Class<? extends Throwable> expected = isGpuAvailable()
				? AssertionError.class : AssumptionViolatedException.class;
		Throwable thrown = thrownBy(() -> requireResource("undeclared resource", UNDECLARED_PROPERTY, false));
		Assert.assertTrue("expected " + expected.getSimpleName() + " but was " + thrown,
				expected.isInstance(thrown));
	}

	/** An absent, undeclared local resource is skipped on every host, GPU or not. */
	@Test(timeout = 30_000)
	public void undeclaredMissingLocalResourceSkips() {
		Assert.assertTrue(thrownBy(() -> requireLocalResource("undeclared local resource", null, false))
				instanceof AssumptionViolatedException);
	}

	/**
	 * Runs {@code check} and returns what it threw, failing if it returned normally: a
	 * missing resource must never let a test proceed as if it were present.
	 *
	 * @param check the resource check to run
	 * @return the throwable {@code check} threw
	 */
	private Throwable thrownBy(Runnable check) {
		try {
			check.run();
		} catch (Throwable t) {
			return t;
		}

		throw new AssertionError("a missing resource returned normally");
	}
}
