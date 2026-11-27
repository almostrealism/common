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

package org.almostrealism.physics.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.physics.RigidBody;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link RigidBody.State} momentum bookkeeping.
 *
 * <p>Angular momentum L relates to angular velocity w through the moment of
 * inertia matrix I by {@code L = I w} — as the {@code l} field javadoc,
 * {@link RigidBody.State#init}, {@link RigidBody.State#update} and
 * {@link RigidBody.State#angularImpulse} all specify. In particular, when the
 * inertia matrix is the identity, angular momentum must equal angular velocity
 * exactly and must NOT be scaled by the (scalar) mass. These tests pin that
 * invariant for the mutator methods.</p>
 */
public class RigidBodyStateTest extends TestSuiteBase {

	/** Absolute tolerance for comparing angular momentum components. */
	private static final double TOL = 1e-6;

	/**
	 * Builds an initialized {@link RigidBody.State} with the given angular velocity,
	 * mass and inertia matrix and all other state vectors zeroed.
	 *
	 * @param w        the initial angular velocity
	 * @param mass     the body mass
	 * @param inertia  the moment of inertia matrix
	 * @return an initialized state
	 */
	private RigidBody.State newState(Vector w, double mass, TransformMatrix inertia) {
		RigidBody.State state = new RigidBody.State();
		state.init(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0), w,
				new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0),
				mass, inertia, 1.0);
		return state;
	}

	/**
	 * Asserts that two vectors are equal component-wise within {@link #TOL}.
	 *
	 * @param expected  the expected vector
	 * @param actual    the actual vector
	 */
	private void assertVectorEquals(Vector expected, Vector actual) {
		Assert.assertEquals(expected.getX(), actual.getX(), TOL);
		Assert.assertEquals(expected.getY(), actual.getY(), TOL);
		Assert.assertEquals(expected.getZ(), actual.getZ(), TOL);
	}

	/**
	 * With an identity inertia matrix, {@link RigidBody.State#setAngularVelocity(Vector)}
	 * must set angular momentum equal to the angular velocity ({@code L = I w = w}) and
	 * must not scale it by the mass.
	 */
	@Test(timeout = 120000)
	public void setAngularVelocityDoesNotScaleMomentumByMass() {
		RigidBody.State state = newState(new Vector(0.0, 0.0, 0.0), 10.0, new TransformMatrix());

		state.setAngularVelocity(new Vector(1.0, 2.0, 3.0));

		assertVectorEquals(new Vector(1.0, 2.0, 3.0), state.getAngularMomentum());
	}

	/**
	 * With an identity inertia matrix, changing the mass via
	 * {@link RigidBody.State#setMass(double)} must keep angular momentum equal to
	 * the angular velocity; angular momentum depends on the inertia matrix and
	 * angular velocity, not on the scalar mass.
	 */
	@Test(timeout = 120000)
	public void setMassDoesNotScaleAngularMomentumByMass() {
		RigidBody.State state = newState(new Vector(1.0, 2.0, 3.0), 10.0, new TransformMatrix());

		state.setMass(20.0);

		assertVectorEquals(new Vector(1.0, 2.0, 3.0), state.getAngularMomentum());
	}
}
