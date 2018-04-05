/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.algebra;

import org.almostrealism.algebra.Mat3f;
import org.almostrealism.algebra.Vector;
import org.almostrealism.util.Defaults;

//notes:
// Another memory optimization would be to store m_1MinvJt in the remaining 3 w components
// which makes the btJacobianEntry memory layout 16 bytes
// if you only are interested in angular part, just feed massInvA and massInvB zero

/**
 * Jacobian entry is an abstraction that allows to describe constraints.
 * It can be used in combination with a constraint solver.
 * Can be used to relate the effect of an impulse to the constraint error.
 * 
 * @author jezek2
 */
@Deprecated
public class JacobianEntry {
	protected final BulletStack stack = BulletStack.get();
	
	public final Vector linearJointAxis = new Vector();
	public final Vector aJ = new Vector();
	public final Vector bJ = new Vector();
	public final Vector m_0MinvJt = new Vector();
	public final Vector m_1MinvJt = new Vector();
	// Optimization: can be stored in the w/last component of one of the vectors
	public double Adiag;

	public JacobianEntry() { }

	/** Constraint between two different RigidBodys. */
	public void init(Mat3f world2A, Mat3f world2B,
					 Vector rel_pos1, Vector rel_pos2,
					 Vector jointAxis, Vector inertiaInvA,
					 double massInvA, Vector inertiaInvB,
					 double massInvB)
	{
		linearJointAxis.setTo(jointAxis);

		aJ.cross(rel_pos1, linearJointAxis);
		world2A.transform(aJ);

		bJ.setTo(linearJointAxis);
		bJ.multiplyBy(-1.0);
		bJ.cross(rel_pos2, bJ);
		world2B.transform(bJ);

		Vector.mul(m_0MinvJt, inertiaInvA, aJ);
		Vector.mul(m_1MinvJt, inertiaInvB, bJ);
		Adiag = massInvA + m_0MinvJt.dotProduct(aJ) +
				massInvB + m_1MinvJt.dotProduct(bJ);

		assert (Adiag > 0f);
	}

	/**
	 * Angular constraint between two different rigidbodies.
	 */
	public void init(Vector jointAxis,
					 Mat3f world2A,
					 Mat3f world2B,
					 Vector inertiaInvA,
					 Vector inertiaInvB) {
		linearJointAxis.setPosition(0f, 0f, 0f);

		aJ.setTo(jointAxis);
		world2A.transform(aJ);

		bJ.setTo(jointAxis);
		bJ.multiplyBy(-1.0);
		world2B.transform(bJ);

		Vector.mul(m_0MinvJt, inertiaInvA, aJ);
		Vector.mul(m_1MinvJt, inertiaInvB, bJ);
		Adiag = m_0MinvJt.dotProduct(aJ) + m_1MinvJt.dotProduct(bJ);

		assert (Adiag > 0f);
	}

	/**
	 * Angular constraint between two different rigidbodies.
	 */
	public void init(Vector axisInA,
					 Vector axisInB,
					 Vector inertiaInvA,
					 Vector inertiaInvB)
	{
		linearJointAxis.setPosition(0f, 0f, 0f);
		aJ.setTo(axisInA);

		bJ.setTo(axisInB);
		bJ.multiplyBy(-1.0);

		Vector.mul(m_0MinvJt, inertiaInvA, aJ);
		Vector.mul(m_1MinvJt, inertiaInvB, bJ);
		Adiag = m_0MinvJt.dotProduct(aJ) + m_1MinvJt.dotProduct(bJ);

		assert (Adiag > 0f);
	}

	/** Constraint on one rigidbody. */
	public void init(
			Mat3f world2A,
			Vector rel_pos1, Vector rel_pos2,
			Vector jointAxis,
			Vector inertiaInvA,
			double massInvA) {
		linearJointAxis.setTo(jointAxis);

		aJ.cross(rel_pos1, jointAxis);
		world2A.transform(aJ);

		bJ.setTo(jointAxis);
		bJ.multiplyBy(-1.0);
		bJ.cross(rel_pos2, bJ);
		world2A.transform(bJ);

		Vector.mul(m_0MinvJt, inertiaInvA, aJ);
		m_1MinvJt.setPosition(0f, 0f, 0f);
		Adiag = massInvA + m_0MinvJt.dotProduct(aJ);

		assert (Adiag > 0f);
	}

	public double getDiagonal() { return Adiag; }

	/**
	 * For two constraints on the same rigidbody (for example vehicle friction).
	 */
	public double getNonDiagonal(JacobianEntry jacB, float massInvA) {
		JacobianEntry jacA = this;
		double lin = massInvA * jacA.linearJointAxis.dotProduct(jacB.linearJointAxis);
		double ang = jacA.m_0MinvJt.dotProduct(jacB.aJ);
		return lin + ang;
	}

	/**
	 * For two constraints on sharing two same rigidbodies
	 * (for example two contact points between two rigidbodies).
	 */
	public double getNonDiagonal(JacobianEntry jacB, float massInvA, float massInvB) {
		stack.vectors.push();

		try {
			JacobianEntry jacA = this;

			Vector lin = stack.vectors.get();
			Vector.mul(lin, jacA.linearJointAxis, jacB.linearJointAxis);

			Vector ang0 = stack.vectors.get();
			Vector.mul(ang0, jacA.m_0MinvJt, jacB.aJ);

			Vector ang1 = stack.vectors.get();
			Vector.mul(ang1, jacA.m_1MinvJt, jacB.bJ);

			Vector lin0 = stack.vectors.get();
			lin0.scale(massInvA, lin);

			Vector lin1 = stack.vectors.get();
			lin1.scale(massInvB, lin);

			Vector sum = stack.vectors.get();
			Vector.add(sum, ang0, ang1, lin0, lin1);

			return sum.getX() + sum.getY() + sum.getZ();
		} finally {
			stack.vectors.pop();
		}
	}

	public double getRelativeVelocity(Vector linvelA, Vector angvelA, Vector linvelB, Vector angvelB) {
		stack.vectors.push();

		try {
			Vector linrel = stack.vectors.get();
			linrel.subtract(linvelA, linvelB);

			Vector angvela = stack.vectors.get();
			Vector.mul(angvela, angvelA, aJ);

			Vector angvelb = stack.vectors.get();
			Vector.mul(angvelb, angvelB, bJ);

			Vector.mul(linrel, linrel, linearJointAxis);

			angvela.add(angvelb);
			angvela.add(linrel);

			double rel_vel2 = angvela.getX() + angvela.getY() + angvela.getZ();
			return rel_vel2 + Defaults.FLT_EPSILON;
		} finally {
			stack.vectors.pop();
		}
	}
	
}
