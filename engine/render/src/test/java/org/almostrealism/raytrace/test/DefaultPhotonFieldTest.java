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

	@Test
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

	@Test
	public void testRemovePhotonsNoneInRange() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{10.0, 10.0, 10.0}, new double[]{0.0, 0.0, 1.0}, 5.0);
		field.addPhoton(new double[]{20.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 3.0);

		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 1.0);
		assertEquals(0, removed);
		assertEquals(2, field.getSize());
	}

	@Test
	public void testRemovePhotonsAllInRange() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{0.1, 0.0, 0.0}, new double[]{1.0, 0.0, 0.0}, 1.0);
		field.addPhoton(new double[]{0.0, 0.1, 0.0}, new double[]{0.0, 1.0, 0.0}, 2.0);
		field.addPhoton(new double[]{0.0, 0.0, 0.1}, new double[]{0.0, 0.0, 1.0}, 3.0);

		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 1.0);
		assertEquals(3, removed);
		assertEquals(0, field.getSize());
	}

	@Test
	public void testRemovePhotonsEmptyField() {
		DefaultPhotonField field = new DefaultPhotonField();
		int removed = field.removePhotons(new double[]{0.0, 0.0, 0.0}, 100.0);
		assertEquals(0, removed);
		assertEquals(0, field.getSize());
	}

	@Test
	public void testRemovePhotonsEnergyReduced() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{1.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 5.0);
		field.addPhoton(new double[]{10.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 7.0);

		field.removePhotons(new double[]{0.0, 0.0, 0.0}, 5.0);

		double remainingEnergy = field.getEnergy(new double[]{10.0, 0.0, 0.0}, 5.0);
		assertEquals(7.0, remainingEnergy, 0.001);
	}
}
