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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.DriverSelection;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Verifies how {@code AR_HARDWARE_DRIVER} values are interpreted, and in
 * particular which backends a value commits to.
 *
 * <p>The distinction under test is between a backend that was named and one the
 * wildcard merely offered. Getting it wrong is not cosmetic: a named backend
 * that fails to initialize must stop {@link org.almostrealism.hardware.Hardware}
 * rather than be skipped, because a suite that quietly ran on a substitute
 * backend reports success for work it never did.</p>
 *
 * <p>These tests exercise parsing alone and need no accelerator, so they run
 * identically on every runner.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend
 * {@code TestSuiteBase}: that class lives in the engine layer, which sits above
 * this module, so depending on it would invert the module graph. Parsing needs
 * none of what it offers.</p>
 */
public class DriverSelectionTest {

	/** The wildcard commits to nothing; every backend it offers is an attempt. */
	@Test(timeout = 30000)
	public void wildcardRequiresNothing() {
		DriverSelection selection = DriverSelection.parse("*", false, false);

		Assert.assertEquals(Arrays.asList(ComputeRequirement.CL, ComputeRequirement.JNI),
				selection.getRequirements());
		Assert.assertTrue("the wildcard must not commit to any backend",
				selection.getRequired().isEmpty());
		Assert.assertFalse(selection.isRequired(ComputeRequirement.CL));
	}

	/** An absent or blank value behaves as the wildcard rather than selecting nothing. */
	@Test(timeout = 30000)
	public void absentValueBehavesAsWildcard() {
		Assert.assertEquals(DriverSelection.parse("*", false, false).getRequirements(),
				DriverSelection.parse(null, false, false).getRequirements());
		Assert.assertEquals(DriverSelection.parse("*", false, false).getRequirements(),
				DriverSelection.parse("   ", false, false).getRequirements());
	}

	/** Naming backends commits to all of them. */
	@Test(timeout = 30000)
	public void namedDriversAreAllRequired() {
		DriverSelection selection = DriverSelection.parse("native,cl", false, false);

		Assert.assertEquals(Arrays.asList(ComputeRequirement.JNI, ComputeRequirement.CL),
				selection.getRequirements());
		Assert.assertTrue(selection.isRequired(ComputeRequirement.JNI));
		Assert.assertTrue(selection.isRequired(ComputeRequirement.CL));
	}

	/**
	 * {@code cl,*} means "anything available, but OpenCL must be among it" —
	 * OpenCL is committed to while the wildcard's additions are not.
	 */
	@Test(timeout = 30000)
	public void wildcardCombinesWithANamedDriver() {
		DriverSelection selection = DriverSelection.parse("cl,*", false, false);

		Assert.assertTrue("the named backend must be required",
				selection.isRequired(ComputeRequirement.CL));
		Assert.assertFalse("a backend the wildcard supplied must not become required",
				selection.isRequired(ComputeRequirement.JNI));
		Assert.assertTrue("the wildcard must still contribute what it can",
				selection.getRequirements().contains(ComputeRequirement.JNI));
	}

	/** A backend both named and offered by the wildcard is attempted once, at the position it was named. */
	@Test(timeout = 30000)
	public void aRepeatedBackendAppearsOnce() {
		DriverSelection selection = DriverSelection.parse("cl,*", false, false);

		Assert.assertEquals(Arrays.asList(ComputeRequirement.CL, ComputeRequirement.JNI),
				selection.getRequirements());
	}

	/** The wildcard offers Metal only where it could exist, and commits to none of it. */
	@Test(timeout = 30000)
	public void wildcardIsPlatformSensitive() {
		DriverSelection apple = DriverSelection.parse("*", true, true);
		Assert.assertEquals(Arrays.asList(ComputeRequirement.JNI,
						ComputeRequirement.MTL, ComputeRequirement.CL),
				apple.getRequirements());
		Assert.assertTrue(apple.getRequired().isEmpty());

		DriverSelection intelMac = DriverSelection.parse("*", true, false);
		Assert.assertFalse(intelMac.getRequirements().contains(ComputeRequirement.MTL));
	}

	/**
	 * Shared memory follows the wildcard standing alone. A value that also names
	 * a backend is stating a preference, so the bridge is not imposed on it.
	 */
	@Test(timeout = 30000)
	public void sharedMemoryOnlyForTheLoneWildcard() {
		Assert.assertTrue(DriverSelection.parse("*", true, true).isSharedMemoryPreferred());
		Assert.assertFalse(DriverSelection.parse("cl,*", true, true).isSharedMemoryPreferred());
		Assert.assertFalse(DriverSelection.parse("*", false, false).isSharedMemoryPreferred());
	}

	/**
	 * Uniform precision follows OpenCL being named. Tolerating OpenCL through the
	 * wildcard must not constrain the precision of every other backend.
	 */
	@Test(timeout = 30000)
	public void uniformPrecisionFollowsAnExplicitOpenClRequest() {
		Assert.assertTrue(DriverSelection.parse("cl", false, false).isUniformPrecisionRequired());
		Assert.assertTrue(DriverSelection.parse("cl,*", false, false).isUniformPrecisionRequired());
		Assert.assertFalse(DriverSelection.parse("*", false, false).isUniformPrecisionRequired());
	}

	/** Abstract requests are committed to as requested, before they resolve to a concrete backend. */
	@Test(timeout = 30000)
	public void abstractRequestsAreRequiredAsRequested() {
		Assert.assertTrue(DriverSelection.parse("gpu", false, false).isRequired(ComputeRequirement.GPU));
		Assert.assertTrue(DriverSelection.parse("cpu", false, false).isRequired(ComputeRequirement.CPU));
	}

	/** Case and surrounding whitespace are tolerated. */
	@Test(timeout = 30000)
	public void tokensAreNormalised() {
		DriverSelection selection = DriverSelection.parse(" NATIVE , Cl ", false, false);

		Assert.assertEquals(Arrays.asList(ComputeRequirement.JNI, ComputeRequirement.CL),
				selection.getRequirements());
		Assert.assertTrue(selection.isRequired(ComputeRequirement.CL));
	}

	/** An unrecognized driver is rejected rather than quietly ignored. */
	@Test(timeout = 30000)
	public void unknownDriversAreRejected() {
		try {
			DriverSelection.parse("cl,vulkan", false, false);
			Assert.fail("an unknown driver should not be accepted");
		} catch (IllegalStateException e) {
			Assert.assertTrue("the message should name the offending driver",
					e.getMessage().contains("vulkan"));
		}
	}

	/** The reported collections are defensive, so a caller cannot alter a parsed selection. */
	@Test(timeout = 30000)
	public void reportedCollectionsAreImmutable() {
		DriverSelection selection = DriverSelection.parse("cl", false, false);

		try {
			selection.getRequirements().add(ComputeRequirement.MTL);
			Assert.fail("requirements should not be modifiable");
		} catch (UnsupportedOperationException expected) {
			// the selection is a value, and reporting it must not expose it to change
		}
	}
}
