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

import org.almostrealism.physics.Clock;
import org.almostrealism.raytrace.AbsorberHashSet;
import org.almostrealism.raytrace.DefaultPhotonField;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the configuration surface of {@link DefaultPhotonField}: the absorber, granularity,
 * logging, ray-tracing and clock accessors, the radius-bounded energy query, the maximum-lifetime
 * unit conversion, and the performance charts created by the constructor.
 */
public class DefaultPhotonFieldConfigTest extends TestSuiteBase {

	/** Verifies {@link DefaultPhotonField#getEnergy} sums only the photons within the given radius. */
	@Test(timeout = 5000)
	public void testGetEnergyWithinRadius() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.addPhoton(new double[]{1.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 4.0);
		field.addPhoton(new double[]{2.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 6.0);
		field.addPhoton(new double[]{50.0, 0.0, 0.0}, new double[]{0.0, 0.0, 1.0}, 100.0);

		assertEquals(10.0, field.getEnergy(new double[]{0.0, 0.0, 0.0}, 5.0), 0.001);
		assertEquals(0.0, field.getEnergy(new double[]{0.0, 0.0, 0.0}, 0.5), 0.001);
	}

	/** Verifies the absorber accessor round-trips, including the clock propagation in {@link DefaultPhotonField#setClock}. */
	@Test(timeout = 5000)
	public void testAbsorberAccessor() {
		DefaultPhotonField field = new DefaultPhotonField();
		StubAbsorber absorber = new StubAbsorber();
		field.setAbsorber(absorber);
		assertSame(absorber, field.getAbsorber());

		Clock clock = new Clock();
		field.setClock(clock);
		assertSame(clock, field.getClock());
		assertSame(clock, absorber.getClock());
	}

	/** Verifies the granularity, logging and ray-tracing accessors round-trip their values. */
	@Test(timeout = 5000)
	public void testScalarAccessors() {
		DefaultPhotonField field = new DefaultPhotonField();

		field.setGranularity(7L);
		assertEquals(7L, field.getGranularity());

		field.setLogFrequency(250L);
		assertEquals(250L, field.getLogFrequency());

		field.setLogFile("/tmp/photons.csv");
		assertEquals("/tmp/photons.csv", field.getLogFile());

		field.setRayTracing(false);
		assertTrue(!field.getRayTracing());
	}

	/** Verifies {@link DefaultPhotonField#setMaxLifetime} and its getter round-trip in lifetime units. */
	@Test(timeout = 5000)
	public void testMaxLifetimeRoundTrip() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.setMaxLifetime(3.5);
		assertEquals(3.5, field.getMaxLifetime(), 1e-9);
	}

	/** Verifies the performance charts are created by the constructor and are stable across calls. */
	@Test(timeout = 5000)
	public void testChartsInitialized() {
		DefaultPhotonField field = new DefaultPhotonField();
		assertNotNull(field.getSizeChart());
		assertNotNull(field.getTimeChart());
		assertNotNull(field.getCostChart());
		assertSame(field.getSizeChart(), field.getSizeChart());
	}

	/**
	 * Verifies a {@link DefaultPhotonField#tick} advances a photon along its direction while keeping
	 * it in the field when its absorber does not absorb it.
	 */
	@Test(timeout = 10000)
	public void testTickAdvancesPhoton() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.setAbsorber(new StubAbsorber());
		field.addPhoton(new double[]{0.0, 0.0, 0.0}, new double[]{1.0, 0.0, 0.0}, 5.0);

		field.tick(1.0e-6);

		assertEquals(1L, field.getSize());
		// The photon has moved far from the origin but is still present in the field.
		assertEquals(0.0, field.getEnergy(new double[]{0.0, 0.0, 0.0}, 1.0), 0.001);
		assertEquals(5.0, field.getEnergy(new double[]{0.0, 0.0, 0.0}, 1.0e9), 0.001);
	}

	/**
	 * Verifies {@link DefaultPhotonField#tick} drops a photon that has travelled beyond the
	 * maximum proximity of an {@link AbsorberHashSet} absorber.
	 */
	@Test(timeout = 10000)
	public void testTickRemovesPhotonBeyondProximity() {
		DefaultPhotonField field = new DefaultPhotonField();
		AbsorberHashSet absorbers = new AbsorberHashSet();
		absorbers.setBound(1.0);
		field.setAbsorber(absorbers);
		field.addPhoton(new double[]{0.0, 0.0, 0.0}, new double[]{1.0, 0.0, 0.0}, 5.0);

		field.tick(1.0e-6);

		assertEquals(0L, field.getSize());
	}

	/**
	 * Verifies {@link DefaultPhotonField#tick} still processes photons with ray tracing disabled,
	 * keeping an un-absorbed photon in the field.
	 */
	@Test(timeout = 10000)
	public void testTickWithoutRayTracing() {
		DefaultPhotonField field = new DefaultPhotonField();
		field.setRayTracing(false);
		field.setAbsorber(new StubAbsorber());
		field.addPhoton(new double[]{0.0, 0.0, 0.0}, new double[]{0.0, 1.0, 0.0}, 2.0);

		field.tick(1.0e-6);

		assertEquals(1L, field.getSize());
	}
}
