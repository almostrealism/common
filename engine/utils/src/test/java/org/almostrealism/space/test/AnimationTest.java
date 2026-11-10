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

	/**
	 * The frame count is the number of frames {@link Animation#run()} writes: the iteration count
	 * divided by the iterations-per-frame ({@code frameDuration / tickDuration}), discarding a
	 * trailing partial frame, and never the physics-step count when several steps elapse per frame.
	 * With a velocity-dependent time step a frame is written every iteration, so the frame count then
	 * equals the iteration count. The durations {@code 0.5} and {@code 0.125} are exact in IEEE-754,
	 * so {@code 0.5 / 0.125} is exactly {@code 4.0} and the assertions need no tolerance.
	 */
	@Test(timeout = 10000)
	public void frameCount() {
		TickAnimation anim = new TickAnimation(0.125);
		anim.setFPS(2.0);

		anim.setIterations(100);
		Assert.assertEquals(25, anim.getFrameCount());

		anim.setIterations(103);
		Assert.assertEquals(25, anim.getFrameCount());

		anim.setVDT(0.5);
		Assert.assertEquals(103, anim.getFrameCount());
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
		RigidSphere body = rigidSphere(3.0);

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
		RigidSphere fast = rigidSphere(4.0);
		RigidSphere slow = rigidSphere(2.0);

		Animation<ShadableSurface> anim = new Animation<>();
		anim.add(fast);
		anim.add(slow);

		Assert.assertEquals(3.0, anim.getAverageLinearVelocity(), 0.0);
	}

	/**
	 * Creates a {@link RigidSphere} whose linear velocity is directed along the x-axis with the given
	 * magnitude and whose remaining rigid-body state (position, forces, torques, angular velocity) is
	 * zero. Because the velocity lies on a single axis, {@code getLinearVelocity().length()} equals
	 * {@code speed} exactly in IEEE-754, which is what the average-velocity assertions rely on.
	 *
	 * @param speed  the desired linear velocity magnitude.
	 * @return a rigid sphere with the requested speed.
	 */
	private static RigidSphere rigidSphere(double speed) {
		return new RigidSphere(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(speed, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				1.0, 1.0, 1.0, 4);
	}

	/**
	 * An {@link Animation} that exposes its protected tick-duration setter, so a test can configure
	 * the physics time step and exercise the frame-count derivation.
	 */
	private static final class TickAnimation extends Animation<ShadableSurface> {
		/**
		 * Creates an animation with the given physics time step.
		 *
		 * @param tickDuration the duration of each physics tick in seconds
		 */
		private TickAnimation(double tickDuration) {
			setTickDuration(tickDuration);
		}
	}
}
