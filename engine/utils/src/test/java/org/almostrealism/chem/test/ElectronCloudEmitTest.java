/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.chem.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.chem.ElectronCloud;
import org.almostrealism.physics.HarmonicAbsorber;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests verifying that {@link ElectronCloud#getNextEmit()} correctly uses
 * queue-based emission rather than the superclass displacement-based mechanism.
 */
public class ElectronCloudEmitTest extends TestSuiteBase {

	/**
	 * Verifies that an empty ElectronCloud returns {@link Integer#MAX_VALUE}
	 * from {@link ElectronCloud#getNextEmit()}, indicating no photons are queued.
	 */
	@Test(timeout = 5000)
	public void emptyQueueReturnsMaxValue() {
		ElectronCloud cloud = new ElectronCloud();
		Assert.assertEquals("Empty queue should return MAX_VALUE",
				(double) Integer.MAX_VALUE, cloud.getNextEmit(), 0.0001);
	}

	/**
	 * Verifies that {@link HarmonicAbsorber#getNextEmit()} uses a displacement-based
	 * mechanism (returns 0 when {@code d >= q}), which differs from ElectronCloud's
	 * queue-based approach. When displacement ({@code d}) starts at 0 and quanta
	 * ({@code q}) is set to 0, the condition {@code d >= q} is true, so the
	 * superclass reports ready-to-emit.
	 */
	@Test(timeout = 5000)
	public void superclassUsesDisplacementBasedEmission() {
		HarmonicAbsorber absorber = new HarmonicAbsorber();
		absorber.setQuanta(0.0);

		Assert.assertEquals("Superclass should return 0 when d >= q (both 0)",
				0.0, absorber.getNextEmit(), 0.0001);
	}

	/**
	 * Proves the override is necessary: when the superclass condition {@code d >= q}
	 * is satisfied (both at 0), {@link HarmonicAbsorber} would report ready-to-emit.
	 * But {@link ElectronCloud} should still report not-ready because its valence
	 * photon queue is empty.
	 */
	@Test(timeout = 5000)
	public void overrideNecessaryWhenSuperclassWouldEmit() {
		HarmonicAbsorber absorber = new HarmonicAbsorber();
		absorber.setQuanta(0.0);
		double superclassResult = absorber.getNextEmit();

		ElectronCloud cloud = new ElectronCloud();
		cloud.setQuanta(0.0);
		double overrideResult = cloud.getNextEmit();

		Assert.assertEquals("Superclass returns 0 (ready to emit)", 0.0, superclassResult, 0.0001);
		Assert.assertEquals("Override returns MAX_VALUE (queue empty)",
				(double) Integer.MAX_VALUE, overrideResult, 0.0001);
		Assert.assertNotEquals("Override and superclass must differ in this scenario",
				superclassResult, overrideResult, 0.0001);
	}

	/**
	 * Verifies that {@link HarmonicAbsorber#getNextEmit()} returns MAX_VALUE when
	 * displacement is less than quanta (no energy absorbed), matching ElectronCloud's
	 * behavior only coincidentally.
	 */
	@Test(timeout = 5000)
	public void superclassReturnsMaxValueWhenNoEnergy() {
		HarmonicAbsorber absorber = new HarmonicAbsorber();
		absorber.setRadius(100.0);
		absorber.setRigidity(1.0);
		absorber.setQuanta(1.0);

		Assert.assertEquals("Superclass returns MAX_VALUE when d < q",
				(double) Integer.MAX_VALUE, absorber.getNextEmit(), 0.0001);
	}
}
