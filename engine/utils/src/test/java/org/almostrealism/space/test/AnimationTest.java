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

package org.almostrealism.space.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.physics.Clock;
import org.almostrealism.primitives.RigidSphere;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.space.Animation;
import org.almostrealism.space.Scene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link Animation}'s configuration accessors, clock management,
 * scene cloning, and the empty-scene average-velocity edge case.
 */
public class AnimationTest extends TestSuiteBase {

	/** A fresh animation starts with a clock, zero time, and default flags off. */
	@Test(timeout = 10000)
	public void defaultState() {
		Animation<ShadableSurface> anim = new Animation<>();
		Assert.assertNotNull(anim.getClock());
		Assert.assertEquals(0.0, anim.getTime(), 1e-12);
		Assert.assertEquals(0.0, anim.getTickDuration(), 1e-12);
		Assert.assertFalse(anim.getSleepEachFrame());
		Assert.assertFalse(anim.getRenderEachFrame());
		Assert.assertFalse(anim.getLogEachFrame());
		Assert.assertNull(anim.getOutputDirectory());
	}

	/** Iteration count round-trips. */
	@Test(timeout = 10000)
	public void iterations() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setIterations(42);
		Assert.assertEquals(42, anim.getIterations());
	}

	/** Frames-per-second is stored as the reciprocal frame duration. */
	@Test(timeout = 10000)
	public void framesPerSecond() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setFPS(25.0);
		Assert.assertEquals(1.0 / 25.0, anim.getFrameDuration(), 1e-12);
	}

	/** The boolean rendering/logging/sleep flags round-trip. */
	@Test(timeout = 10000)
	public void booleanFlags() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setSleepEachFrame(true);
		anim.setRenderEachFrame(true);
		anim.setLogEachFrame(true);
		Assert.assertTrue(anim.getSleepEachFrame());
		Assert.assertTrue(anim.getRenderEachFrame());
		Assert.assertTrue(anim.getLogEachFrame());
	}

	/** The output directory round-trips. */
	@Test(timeout = 10000)
	public void outputDirectory() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setOutputDirectory("/tmp/frames/");
		Assert.assertEquals("/tmp/frames/", anim.getOutputDirectory());
	}

	/** A replacement clock is stored and returned. */
	@Test(timeout = 10000)
	public void clockReplacement() {
		Animation<ShadableSurface> anim = new Animation<>();
		Clock clock = new Clock();
		anim.setClock(clock);
		Assert.assertSame(clock, anim.getClock());
	}

	/** getScene returns a Scene clone distinct from the animation instance. */
	@Test(timeout = 10000)
	public void getSceneReturnsClone() {
		Animation<ShadableSurface> anim = new Animation<>();
		Scene scene = anim.getScene();
		Assert.assertNotNull(scene);
		Assert.assertNotSame(anim, scene);
	}

	/** An empty scene reports a mean linear velocity of 0.0, per the documented contract. */
	@Test(timeout = 10000)
	public void averageVelocityOfEmptySceneIsZero() {
		Animation<ShadableSurface> anim = new Animation<>();
		Assert.assertEquals(0.0, anim.getAverageLinearVelocity(), 0.0);
	}

	/**
	 * A scene containing only non-{@link org.almostrealism.physics.RigidBody} surfaces reports 0.0,
	 * because there are no rigid bodies to average over even though the scene is not empty.
	 */
	@Test(timeout = 10000)
	public void averageVelocityIgnoresNonRigidSurfaces() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.add(new Sphere());
		Assert.assertEquals(0.0, anim.getAverageLinearVelocity(), 0.0);
	}

	/**
	 * The average is taken over the rigid bodies alone. A single rigid body of speed 3.0 sharing the
	 * scene with a static, non-rigid {@link Sphere} reports 3.0, not the 1.5 that would result from
	 * dividing by the total surface count. Both {@code sqrt(3^2)} and {@code 3.0/1} are exact in
	 * IEEE-754, so the assertion needs no tolerance.
	 */
	@Test(timeout = 10000)
	public void averageVelocityCountsOnlyRigidBodies() {
		RigidSphere body = new RigidSphere(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 3.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				1.0, 1.0, 1.0, 4);

		Animation<ShadableSurface> anim = new Animation<>();
		anim.add(body);
		anim.add(new Sphere());

		Assert.assertEquals(3.0, anim.getAverageLinearVelocity(), 0.0);
	}

	/**
	 * With two rigid bodies the average is the mean of their speeds: speeds 4.0 and 2.0 average to
	 * {@code (4.0 + 2.0) / 2 = 3.0} exactly.
	 */
	@Test(timeout = 10000)
	public void averageVelocityIsMeanOfRigidBodySpeeds() {
		RigidSphere fast = new RigidSphere(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(4.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				1.0, 1.0, 1.0, 4);
		RigidSphere slow = new RigidSphere(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(2.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				1.0, 1.0, 1.0, 4);

		Animation<ShadableSurface> anim = new Animation<>();
		anim.add(fast);
		anim.add(slow);

		Assert.assertEquals(3.0, anim.getAverageLinearVelocity(), 0.0);
	}
}
