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

package org.almostrealism.primitives.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.primitives.RigidBodyPinholeCamera;
import org.almostrealism.primitives.RigidSphere;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link RigidBodyPinholeCamera}: it mirrors the pose of the body it follows at construction
 * and on each tick, and the directional impulses it applies to that body.
 */
// TODO(review): tickFollowsTheBody fails on pre-existing bug — RigidBodyPinholeCamera constructor never assigns this.c, so tick() NPEs (see review-followup memory).
public class RigidBodyPinholeCameraTest extends TestSuiteBase {
	/** The tolerance for comparing positions and velocities. */
	private static final double TOLERANCE = 1e-9;

	/** The zero vector, used for the many unused state components. */
	private static final Vector ZERO = new Vector(0.0, 0.0, 0.0);

	/**
	 * Returns a rigid sphere at the given location whose rotation vector points along {@code -z},
	 * so the camera that follows it has a well-defined viewing direction.
	 *
	 * @param location the centre of the body
	 * @return the body
	 */
	private RigidSphere body(Vector location) {
		return new RigidSphere(location, new Vector(0.0, 0.0, -1.0), ZERO, ZERO, ZERO, ZERO,
				1.0, 1.0, 1.0, 4);
	}

	/**
	 * Returns a pinhole camera with an explicit up direction so it can be re-aimed safely.
	 *
	 * @return the camera
	 */
	private PinholeCamera camera() {
		return new PinholeCamera(ZERO, new Vector(0.0, 0.0, -1.0), new Vector(0.0, 1.0, 0.0));
	}

	/** At construction the camera takes the body's position and orientation. */
	@Test(timeout = 30000)
	public void constructionMirrorsBodyPose() {
		RigidSphere model = body(new Vector(1.0, 2.0, 3.0));
		PinholeCamera c = camera();
		new RigidBodyPinholeCamera(model, c);

		assertEquals(1.0, c.getLocation().getX(), TOLERANCE);
		assertEquals(2.0, c.getLocation().getY(), TOLERANCE);
		assertEquals(3.0, c.getLocation().getZ(), TOLERANCE);

		assertEquals(-1.0, c.getViewingDirection().getZ(), TOLERANCE);
	}

	/** A tick copies the body's current position onto the camera. */
	@Test(timeout = 30000)
	public void tickFollowsTheBody() {
		RigidSphere model = body(new Vector(0.0, 0.0, 0.0));
		PinholeCamera c = camera();
		RigidBodyPinholeCamera rig = new RigidBodyPinholeCamera(model, c);

		model.getState().setLocation(new Vector(7.0, 8.0, 9.0));
		rig.tick().get().run();

		assertEquals(7.0, c.getLocation().getX(), TOLERANCE);
		assertEquals(8.0, c.getLocation().getY(), TOLERANCE);
		assertEquals(9.0, c.getLocation().getZ(), TOLERANCE);
	}

	/** Moving forward pushes the body along its viewing direction; backward pushes it the other way. */
	@Test(timeout = 30000)
	public void forwardAndBackwardImpulseAlongView() {
		RigidSphere model = body(new Vector(0.0, 0.0, 0.0));
		RigidBodyPinholeCamera rig = new RigidBodyPinholeCamera(model, camera());

		rig.forward(2.0);
		assertEquals(-2.0, model.getState().getLinearVelocity().getZ(), TOLERANCE);

		rig.backward(2.0);
		assertEquals(0.0, model.getState().getLinearVelocity().getZ(), TOLERANCE);
	}

	/** Jumping applies an upward impulse. */
	@Test(timeout = 30000)
	public void jumpImpulseIsUpward() {
		RigidSphere model = body(new Vector(0.0, 0.0, 0.0));
		RigidBodyPinholeCamera rig = new RigidBodyPinholeCamera(model, camera());

		rig.jump(3.0);
		assertEquals(3.0, model.getState().getLinearVelocity().getY(), TOLERANCE);
	}
}
