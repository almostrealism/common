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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.electrostatic.PotentialMap;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.Volume;
import org.almostrealism.physics.VolumeAbsorber;
import org.almostrealism.raytrace.AbsorberHashSet;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link AbsorberHashSet} and its nested {@link AbsorberHashSet.StoredItem}, covering
 * the CPU-only surface: membership and position lookup, configuration accessors, emitter
 * selection and emission, nearest-absorber distance queries, and the no-intersection shading
 * and value paths.
 */
public class AbsorberHashSetTest extends TestSuiteBase {

	/** A {@link Volume} with a fixed intersection distance, used to drive distance queries. */
	private class StubVolume implements Volume<PackedCollection> {
		/** The fixed distance this stub reports from {@link #intersect(Vector, Vector)}. */
		private final double intersectDistance;

		StubVolume(double intersectDistance) { this.intersectDistance = intersectDistance; }

		@Override
		public boolean inside(Producer<PackedCollection> x) { return true; }

		@Override
		public double intersect(Vector p, Vector d) { return intersectDistance; }

		@Override
		public double[] getSurfaceCoords(Producer<PackedCollection> xyz) { return new double[] { 0.0, 0.0 }; }

		@Override
		public double[] getSpatialCoords(double[] uv) { return new double[] { uv[0], uv[1], 0.0 }; }

		@Override
		public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> point) { return vector(0.0, 0.0, 1.0); }

		@Override
		public Producer<PackedCollection> getValueAt(Producer<PackedCollection> point) { return vector(1.0, 1.0, 1.0); }
	}

	/** An {@link Absorber} that is also a {@link Volume}, exercising the direct-volume distance branch. */
	private class StubVolumeAndAbsorber extends StubVolume implements Absorber {
		StubVolumeAndAbsorber(double intersectDistance) { super(intersectDistance); }

		@Override
		public boolean absorb(Vector x, Vector p, double energy) { return false; }

		@Override
		public Producer<PackedCollection> emit() { return null; }

		@Override
		public double getEmitEnergy() { return 0.0; }

		@Override
		public double getNextEmit() { return Integer.MAX_VALUE; }

		@Override
		public Producer<PackedCollection> getEmitPosition() { return null; }

		@Override
		public void setClock(Clock c) { }

		@Override
		public Clock getClock() { return null; }
	}

	/** Verifies adding absorbers grows the set and reports the running size. */
	@Test(timeout = 30000)
	public void testAddAbsorberIncreasesSize() {
		AbsorberHashSet set = new AbsorberHashSet();
		assertEquals(0, set.size());
		assertEquals(1, set.addAbsorber(new StubAbsorber(), vector(1.0, 0.0, 0.0)));
		assertEquals(2, set.addAbsorber(new StubAbsorber(), vector(2.0, 0.0, 0.0)));
		assertEquals(2, set.size());
	}

	/** Verifies {@link AbsorberHashSet#getLocation} returns the stored position producer, or null if absent. */
	@Test(timeout = 30000)
	public void testGetLocation() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		Producer<PackedCollection> pos = vector(3.0, 4.0, 5.0);
		set.addAbsorber(a, pos);

		assertSame(pos, set.getLocation(a));
		assertNull(set.getLocation(new StubAbsorber()));
	}

	/** Verifies the scalar configuration accessors round-trip their values. */
	@Test(timeout = 5000)
	public void testConfigurationAccessors() {
		AbsorberHashSet set = new AbsorberHashSet();

		set.setOrderMethod(AbsorberHashSet.POPULAR_ORDER);
		assertEquals(AbsorberHashSet.POPULAR_ORDER, set.getOrderMethod());

		set.setSpreadAngle(0.75);
		assertEquals(0.75, set.getSpreadAngle(), 0.0);

		set.setSpreadCount(9);
		assertEquals(9, set.getSpreadCount());

		set.setFastAbsorption(false);
		assertTrue(!set.getFastAbsorption());

		PotentialMap map = p -> 0.0;
		set.setPotentialMap(map);
		assertSame(map, set.getPotentialMap());
	}

	/**
	 * Verifies {@link AbsorberHashSet#setBound} clamps max proximity to {@code 2 * bound} and that
	 * {@link AbsorberHashSet#setMaxProximity} further clamps to the smaller of its argument and that bound.
	 */
	@Test(timeout = 5000)
	public void testBoundAndProximityClamping() {
		AbsorberHashSet set = new AbsorberHashSet();
		set.setBound(10.0);
		assertEquals(10.0, set.getBound(), 0.0);
		assertEquals(20.0, set.getMaxProximity(), 0.0);

		set.setMaxProximity(5.0);
		assertEquals(5.0, set.getMaxProximity(), 0.0);

		set.setMaxProximity(100.0);
		assertEquals(20.0, set.getMaxProximity(), 0.0);
	}

	/** Verifies {@link AbsorberHashSet#setColor} drives the color returned by {@link AbsorberHashSet#getValueAt}. */
	@Test(timeout = 30000)
	public void testColorValue() {
		AbsorberHashSet set = new AbsorberHashSet();
		set.setColor(0.1, 0.2, 0.3);

		RGB c = new RGB(set.getValueAt(vector(0.0, 0.0, 0.0)).get().evaluate(), 0);
		assertEquals(0.1, c.getRed(), 1e-6);
		assertEquals(0.2, c.getGreen(), 1e-6);
		assertEquals(0.3, c.getBlue(), 1e-6);
	}

	/** Verifies the shading face flags and the intentionally-unimplemented operator accessors. */
	@Test(timeout = 5000)
	public void testShadeFlagsAndNullOperators() {
		AbsorberHashSet set = new AbsorberHashSet();
		assertTrue(!set.getShadeBack());
		assertTrue(set.getShadeFront());
		assertNull(set.get());
		assertNull(set.expect());
	}

	/** Verifies {@link AbsorberHashSet#calculateBoundingSolid} is not implemented and throws. */
	@Test(timeout = 5000)
	public void testCalculateBoundingSolidThrows() {
		AbsorberHashSet set = new AbsorberHashSet();
		try {
			set.calculateBoundingSolid();
			fail("Expected RuntimeException");
		} catch (RuntimeException expected) {
			assertTrue(expected.getMessage().contains("getBoundingSolid"));
		}
	}

	/** Verifies {@link AbsorberHashSet#setClock} stores the clock and propagates it to child absorbers. */
	@Test(timeout = 30000)
	public void testSetClockPropagates() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));

		Clock clock = new Clock();
		set.setClock(clock);
		assertSame(clock, set.getClock());
		assertSame(clock, a.getClock());
	}

	/** Verifies emission queries on an empty set report "nothing to emit". */
	@Test(timeout = 5000)
	public void testEmitWithNoEmitter() {
		AbsorberHashSet set = new AbsorberHashSet();
		assertNull(set.emit());
		assertEquals(0.0, set.getEmitEnergy(), 0.0);
		assertEquals(Integer.MAX_VALUE, set.getNextEmit(), 0.0);
		assertNull(set.getEmitPosition());
	}

	/**
	 * Verifies emitter selection chooses an absorber whose next-emit time precedes the clock tick
	 * interval, and that the energy, position (absorber position plus stored position) and emitted
	 * direction are drawn from that absorber.
	 */
	@Test(timeout = 30000)
	public void testSelectEmitterAndEmit() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		a.nextEmit = -1.0;
		a.energy = 2.5;
		a.emitPos = vector(1.0, 0.0, 0.0);
		a.emitDir = vector(0.0, 0.0, 1.0);
		set.addAbsorber(a, vector(0.0, 2.0, 0.0));
		set.setClock(new Clock());

		assertEquals(-1.0, set.getNextEmit(), 0.0);
		assertEquals(2.5, set.getEmitEnergy(), 0.0);

		double[] pos = set.getEmitPosition().get().evaluate().toArray();
		assertEquals(1.0, pos[0], 1e-6);
		assertEquals(2.0, pos[1], 1e-6);
		assertEquals(0.0, pos[2], 1e-6);

		double[] dir = set.emit().get().evaluate().toArray();
		assertEquals(0.0, dir[0], 1e-6);
		assertEquals(0.0, dir[1], 1e-6);
		assertEquals(1.0, dir[2], 1e-6);
	}

	/**
	 * Verifies {@link AbsorberHashSet#getDistance} returns the smallest intersection distance over
	 * both the {@link VolumeAbsorber} and direct-{@link Volume} branches.
	 */
	@Test(timeout = 30000)
	public void testGetDistanceSelectsNearest() {
		AbsorberHashSet set = new AbsorberHashSet();
		set.addAbsorber(new VolumeAbsorber(new StubVolume(2.0), new StubAbsorber()), vector(0.0, 0.0, 0.0));
		set.addAbsorber(new StubVolumeAndAbsorber(5.0), vector(0.0, 0.0, 0.0));

		double d = set.getDistance(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, -1.0));
		assertEquals(2.0, d, 1e-6);
	}

	/** Verifies iterating the set yields one {@link AbsorberHashSet.StoredItem} per added absorber. */
	@Test(timeout = 30000)
	public void testIteratorYieldsStoredItems() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		StubAbsorber b = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));
		set.addAbsorber(b, vector(1.0, 0.0, 0.0));

		int count = 0;
		Iterator itr = set.iterator();
		while (itr.hasNext()) {
			AbsorberHashSet.StoredItem item = (AbsorberHashSet.StoredItem) itr.next();
			assertTrue(item.getAbsorber() == a || item.getAbsorber() == b);
			count++;
		}
		assertEquals(2, count);
	}

	/** Verifies {@link AbsorberHashSet#absorberIterator} exposes the underlying absorbers directly. */
	@Test(timeout = 30000)
	public void testAbsorberIterator() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));

		Iterator itr = set.absorberIterator();
		assertTrue(itr.hasNext());
		assertSame(a, itr.next());
		assertTrue(!itr.hasNext());
	}

	/** Verifies {@link AbsorberHashSet#init} enables the shared iterator while still yielding every item. */
	@Test(timeout = 30000)
	public void testInitEnablesSharedIterator() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));
		set.init();

		int count = 0;
		Iterator itr = set.iterator();
		while (itr.hasNext()) {
			itr.next();
			count++;
		}
		assertEquals(1, count);
	}

	/**
	 * Verifies {@link AbsorberHashSet#absorb} returns true once a contained absorber reports that it
	 * absorbed the photon.
	 */
	@Test(timeout = 30000)
	public void testAbsorbDelegatesToMember() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		a.absorbResult = true;
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));

		assertTrue(set.absorb(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
	}

	/**
	 * Verifies {@link AbsorberHashSet#getDistance} reports the unconstrained-absorber default of 0.0
	 * when the only member is neither a {@link Volume} nor a {@link VolumeAbsorber}.
	 */
	@Test(timeout = 30000)
	public void testGetDistanceUnconstrainedAbsorber() {
		AbsorberHashSet set = new AbsorberHashSet();
		set.addAbsorber(new StubAbsorber(), vector(0.0, 0.0, 0.0));

		double d = set.getDistance(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, -1.0));
		assertEquals(0.0, d, 1e-6);
	}

	/**
	 * Verifies {@link AbsorberHashSet.StoredItem#getVolume} resolves the volume of a wrapped
	 * {@link VolumeAbsorber}.
	 */
	@Test(timeout = 30000)
	public void testStoredItemResolvesVolume() {
		StubVolume volume = new StubVolume(1.0);
		VolumeAbsorber absorber = new VolumeAbsorber(volume, new StubAbsorber());
		AbsorberHashSet.StoredItem item = new AbsorberHashSet.StoredItem(absorber, vector(0.0, 0.0, 0.0));
		assertSame(volume, item.getVolume());
	}

	/** Verifies {@link AbsorberHashSet#shade} returns black when no surface has been intersected yet. */
	@Test(timeout = 30000)
	public void testShadeWithoutIntersectionIsBlack() {
		AbsorberHashSet set = new AbsorberHashSet();
		RGB c = (RGB) set.shade(null).get().evaluate(new Object[0]);
		assertEquals(0.0, c.getRed(), 1e-6);
		assertEquals(0.0, c.getGreen(), 1e-6);
		assertEquals(0.0, c.getBlue(), 1e-6);
	}

	/**
	 * Verifies the random-order iterator (enabled via {@link AbsorberHashSet#RANDOM_ORDER} after
	 * {@link AbsorberHashSet#init}) still visits every stored item exactly once.
	 */
	@Test(timeout = 30000)
	public void testRandomOrderIteratorVisitsAll() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		StubAbsorber b = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));
		set.addAbsorber(b, vector(1.0, 0.0, 0.0));
		set.setOrderMethod(AbsorberHashSet.RANDOM_ORDER);
		set.init();

		int count = 0;
		boolean sawA = false;
		boolean sawB = false;
		Iterator itr = set.iterator(true);
		while (itr.hasNext()) {
			AbsorberHashSet.StoredItem item = (AbsorberHashSet.StoredItem) itr.next();
			sawA |= item.getAbsorber() == a;
			sawB |= item.getAbsorber() == b;
			count++;
		}
		assertEquals(2, count);
		assertTrue(sawA && sawB);
	}

	/**
	 * Verifies {@link AbsorberHashSet#removeAbsorber(Absorber)} leaves the set size unchanged because
	 * a {@link AbsorberHashSet.StoredItem} never equals a bare absorber (a pre-existing quirk).
	 */
	@Test(timeout = 30000)
	public void testRemoveAbsorberByReferenceIsNoOp() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));

		assertEquals(1, set.removeAbsorber(a));
		assertEquals(1, set.size());
	}

	/**
	 * Verifies {@link AbsorberHashSet#setColorBufferDimensions(int, int, double)} applies to the most
	 * recently added absorber's stored item.
	 */
	@Test(timeout = 30000)
	public void testSetColorBufferDimensionsOnLastAdded() {
		AbsorberHashSet set = new AbsorberHashSet();
		StubAbsorber a = new StubAbsorber();
		set.addAbsorber(a, vector(0.0, 0.0, 0.0));
		set.setColorBufferDimensions(2, 3, 1.0);

		Iterator itr = set.iterator();
		AbsorberHashSet.StoredItem item = (AbsorberHashSet.StoredItem) itr.next();
		int[] dims = item.getColorBufferDimensions();
		assertEquals(2, dims[0]);
		assertEquals(3, dims[1]);
	}

	/**
	 * Verifies a {@link AbsorberHashSet.StoredItem} accumulates emitted color into its buffer so that
	 * {@link AbsorberHashSet.StoredItem#getColorAt} reports a positive contribution afterward.
	 */
	@Test(timeout = 30000)
	public void testStoredItemColorAccumulation() {
		AbsorberHashSet.StoredItem item = new AbsorberHashSet.StoredItem();
		item.setColorBufferSize(2, 2, 1.0);
		item.addExitance(0.5, 0.5, vector(0.0, 0.0, 1.0), true);
		item.addColor(0.5, 0.5, true, new RGB(0.9, 0.0, 0.0));

		RGB c = item.getColorAt(0.5, 0.5, true, false);
		assertTrue(c.getRed() > 0.0);
	}

	/** Verifies a {@link AbsorberHashSet.StoredItem} exposes the absorber and position it was constructed with. */
	@Test(timeout = 5000)
	public void testStoredItemConstruction() {
		StubAbsorber a = new StubAbsorber();
		Producer<PackedCollection> pos = vector(1.0, 2.0, 3.0);
		AbsorberHashSet.StoredItem item = new AbsorberHashSet.StoredItem(a, pos);
		assertSame(a, item.getAbsorber());
		assertSame(pos, item.getPosition());
		assertTrue(item.toString().contains("StoredItem["));
	}

	/** Verifies the boolean/reference accessors of {@link AbsorberHashSet.StoredItem} round-trip. */
	@Test(timeout = 5000)
	public void testStoredItemAccessors() {
		AbsorberHashSet.StoredItem item = new AbsorberHashSet.StoredItem();

		StubAbsorber a = new StubAbsorber();
		item.setAbsorber(a);
		assertSame(a, item.getAbsorber());

		Producer<PackedCollection> pos = vector(0.0, 0.0, 0.0);
		item.setPosition(pos);
		assertSame(pos, item.getPosition());

		item.setChecked(true);
		assertTrue(item.isChecked());
		item.setFast(true);
		assertTrue(item.isFast());
		item.setHighlight(true);
		assertTrue(item.isHighlight());
	}

	/**
	 * Verifies {@link AbsorberHashSet.StoredItem} reports a zero color buffer scale before a buffer
	 * is allocated, and the configured dimensions and scale afterward.
	 */
	@Test(timeout = 5000)
	public void testStoredItemColorBuffer() {
		AbsorberHashSet.StoredItem item = new AbsorberHashSet.StoredItem();
		assertEquals(0.0, item.getColorBufferScale(), 0.0);

		item.setColorBufferSize(4, 8, 2.0);
		int[] dims = item.getColorBufferDimensions();
		assertEquals(4, dims[0]);
		assertEquals(8, dims[1]);
		assertEquals(2.0, item.getColorBufferScale(), 0.0);
	}
}
