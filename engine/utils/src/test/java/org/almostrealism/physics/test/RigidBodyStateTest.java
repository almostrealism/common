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
	 * Builds a diagonal (non-identity) moment of inertia matrix with the given principal
	 * moments, so that {@code transformAsOffset(w)} yields {@code (ix*wx, iy*wy, iz*wz)}.
	 *
	 * @param ix  moment of inertia about the x axis
	 * @param iy  moment of inertia about the y axis
	 * @param iz  moment of inertia about the z axis
	 * @return a diagonal inertia matrix
	 */
	private TransformMatrix diagonalInertia(double ix, double iy, double iz) {
		return new TransformMatrix(new double[][] {
				{ix, 0.0, 0.0, 0.0},
				{0.0, iy, 0.0, 0.0},
				{0.0, 0.0, iz, 0.0},
				{0.0, 0.0, 0.0, 1.0}});
	}

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

	/**
	 * With a non-identity diagonal inertia tensor, {@link RigidBody.State#setAngularVelocity(Vector)}
	 * must set angular momentum to {@code L = I w}, i.e. each component of the angular velocity
	 * scaled by the corresponding principal moment of inertia. This proves the tensor is actually
	 * applied, not merely copied (an {@code l = w} implementation would fail here).
	 */
	@Test(timeout = 120000)
	public void setAngularVelocityAppliesInertiaTensor() {
		RigidBody.State state = newState(new Vector(0.0, 0.0, 0.0), 10.0, diagonalInertia(2.0, 3.0, 4.0));

		state.setAngularVelocity(new Vector(5.0, 7.0, 9.0));

		assertVectorEquals(new Vector(10.0, 21.0, 36.0), state.getAngularMomentum());
	}

	/**
	 * With a non-identity diagonal inertia tensor, {@link RigidBody.State#setMass(double)} must
	 * recompute angular momentum as {@code L = I w} from the current inertia tensor and angular
	 * velocity, independent of the scalar mass. This proves the tensor is actually applied, not
	 * merely copied, and that the result does not depend on the mass.
	 */
	@Test(timeout = 120000)
	public void setMassAppliesInertiaTensorIndependentOfMass() {
		RigidBody.State state = newState(new Vector(5.0, 7.0, 9.0), 10.0, diagonalInertia(2.0, 3.0, 4.0));

		state.setMass(20.0);

		assertVectorEquals(new Vector(10.0, 21.0, 36.0), state.getAngularMomentum());
	}
}
