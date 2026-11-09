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

package org.almostrealism.render.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.render.PhotonFieldAnimation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Tests that {@link PhotonFieldAnimation} keeps its absorber set in step with its members, however
 * they are added and removed.
 */
public class PhotonFieldAnimationTest extends TestSuiteBase {
	/** A sphere that is also an absorber, which absorbs and emits nothing. */
	private static class AbsorbingSphere extends Sphere implements Absorber {
		/** The clock given to this absorber. */
		private Clock clock;

		@Override
		public boolean absorb(Vector x, Vector p, double energy) { return false; }

		@Override
		public Producer<PackedCollection> emit() { return null; }

		@Override
		public double getEmitEnergy() { return 0.0; }

		@Override
		public double getNextEmit() { return Double.MAX_VALUE; }

		@Override
		public Producer<PackedCollection> getEmitPosition() { return null; }

		@Override
		public void setClock(Clock c) { this.clock = c; }

		@Override
		public Clock getClock() { return clock; }
	}

	/**
	 * Asserts the number of absorbers in the animation's absorber set.
	 *
	 * @param expected  the expected number
	 * @param animation the animation
	 */
	private void assertAbsorbers(int expected, PhotonFieldAnimation<ShadableSurface> animation) {
		assertEquals(expected, animation.getAbsorber().size());
	}

	/** Single additions, replacements and removals of absorbers reach the absorber set. */
	@Test(timeout = 30000)
	public void singleChangesReachAbsorberSet() {
		PhotonFieldAnimation<ShadableSurface> animation = new PhotonFieldAnimation<>();
		AbsorbingSphere a = new AbsorbingSphere();
		AbsorbingSphere b = new AbsorbingSphere();

		animation.add(a);
		animation.add(new Sphere());
		animation.add(0, b);
		assertAbsorbers(2, animation);

		animation.set(0, new Sphere());
		assertAbsorbers(1, animation);

		animation.remove(a);
		assertAbsorbers(0, animation);

		animation.add(a);
		animation.remove(animation.size() - 1);
		assertAbsorbers(0, animation);
	}

	/** Bulk additions and removals of absorbers reach the absorber set. */
	@Test(timeout = 30000)
	public void bulkChangesReachAbsorberSet() {
		PhotonFieldAnimation<ShadableSurface> animation = new PhotonFieldAnimation<>();
		AbsorbingSphere a = new AbsorbingSphere();
		AbsorbingSphere b = new AbsorbingSphere();
		AbsorbingSphere c = new AbsorbingSphere();

		animation.addAll(List.of(a, b));
		animation.addAll(0, List.of(c, new Sphere()));
		assertAbsorbers(3, animation);

		animation.retainAll(List.of(a, b));
		assertAbsorbers(2, animation);
		assertEquals(2, animation.size());

		animation.removeAll(List.of(a));
		assertAbsorbers(1, animation);

		animation.clear();
		assertAbsorbers(0, animation);
	}
}
