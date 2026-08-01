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

package org.almostrealism.raytrace.test;

import org.almostrealism.raytrace.DefaultPhotonField;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link DefaultPhotonField}, verifying photon management operations
 * including add, remove, size, and energy queries.
 */
public class DefaultPhotonFieldTest extends TestSuiteBase {

	/** Verifies that photons within the given radius are removed and the count is updated. */
	@Test(timeout = 5000)
	public void testRemovePhotonsWithinRadius() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{1.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 5.0);
		field.addPhoton(new double[]{2.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 3.0);
		field.addPhoton(new double[]{10.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 7.0);
		assertEquals(3, field.getSize());

		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 5.0);
		assertEquals(2, removed);
		assertEquals(1, field.getSize());
	}

	/** Verifies that no photons are removed when none fall within the given radius. */
	@Test(timeout = 5000)
	public void testRemovePhotonsNoneInRange() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{10.0, 10.0, 10.0}, new double[]{0.0, 0.0, 1.0}, 5.0);
		field.addPhoton(new double[]{20.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 3.0);

		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 1.0);
		assertEquals(0, removed);
		assertEquals(2, field.getSize());
	}

	/** Verifies that all photons are removed when all fall within the given radius. */
	@Test(timeout = 5000)
	public void testRemovePhotonsAllInRange() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{0.1, 0.0, 0.0}, new double[]{1.0, 0.0, 0.0}, 1.0);
		field.addPhoton(new double[]{0.0, 0.1, 0.0}, new double[]{0.0, 1.0, 0.0}, 2.0);
		field.addPhoton(new double[]{0.0, 0.0, 0.1}, new double[]{0.0, 0.0, 1.0}, 3.0);

		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 1.0);
		assertEquals(3, removed);
		assertEquals(0, field.getSize());
	}

	/** Verifies that removing from an empty field returns zero and does not throw. */
	@Test(timeout = 5000)
	public void testRemovePhotonsEmptyField() {
		DefaultPhotonField field = new DefaultPhotonField();
		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 100.0);
		assertEquals(0, removed);
		assertEquals(0, field.getSize());
	}

	/** Verifies that energy reflects only remaining photons after removal. */
	@Test(timeout = 5000)
	public void testRemovePhotonsEnergyReduced() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{1.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 5.0);
		field.addPhoton(new double[]{10.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 7.0);

		field.removePhotons(new double[]{0.0, 0.0, 0.0}, 5.0);

		double remainingEnergy = field.getEnergy(new double[]{10.0, 0.0, 0.0}, 5.0);
		assertEquals(7.0, remainingEnergy, 0.001);
	}
}
