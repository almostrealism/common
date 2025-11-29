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

package org.almostrealism.primitives;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Camera;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.physics.RigidBody;
import org.almostrealism.space.Plane;

import java.awt.*;


/**
 * @author  Michael Murray
 */
public class RigidPlane extends Plane implements RigidBody {
	private State state;
	
	private TransformMatrix rotateXMatrix, rotateYMatrix, rotateZMatrix;

	public RigidPlane() {
		this(new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0),
				new Vector(0.0, 0.0, 0.0),
				1.0, 1.0);
	}

	/**
	 * Constructs a new Plane object using the specified initial rigid body state values.
	 * 
	 * @param x  location
	 * @param r  rotation
	 * @param v  linear velocity
	 * @param w  angular velocity
	 * @param f  force
	 * @param t  torque
	 * @param mass  mass
	 * @param e  coefficient of restitution
	 */
	public RigidPlane(Vector x, Vector r, Vector v, Vector w, Vector f, Vector t, double mass, double e) {
		super(Plane.XZ);
		
		this.state = new State();
		this.state.init(x, r, v, w, f, t, mass, new TransformMatrix(new double[][] {{1.0, 0.0, 0.0, 0.0},
				{0.0, 1.0, 0.0, 0.0},
				{0.0, 0.0, 1.0, 0.0},
				{0.0, 0.0, 0.0, 1.0}}), e);
	}
	
	// public void angularImpulse(Vector impulse) {
	//    System.out.println("Angular impulse on plane.");
	//}
	
	/**
	 * @see  RigidBody#intersect(RigidBody)
	 */
	public Vector[] intersect(RigidBody b) {
		if (b instanceof RigidSphere) {
			State s = ((RigidSphere)b).getState();
			double d = this.state.x.getY() - s.x.getY();
			
			if (Math.abs(d) <= ((RigidSphere)b).getRadius()) {
				if (d >= 0)
					return new Vector[] {new Vector(s.x.getX(), s.x.getY(), s.x.getZ()), new Vector(0.0, -1.0, 0.0)};
				else
					return new Vector[] {new Vector(s.x.getX(), this.state.x.getY(), s.x.getZ()), new Vector(0.0, 1.0, 0.0)};
			} else {
				return new Vector[0];
			}
		} else {
			return new Vector[0];
		}
	}
	
	/**
	 * @see  RigidBody#draw(Camera, Graphics, double, double, double)
	 */
	public void draw(Camera c, Graphics g, double ox, double oy, double scale) {
	}
	
	/**
	 * @see  RigidBody#updateModel()
	 */
	public void updateModel() {
		// Vector rn = super.r.divide(super.r.length());
		
		// this.rotateXMatrix = TransformMatrix.createRotateXMatrix(Math.acos(rn.getX()));
		// this.rotateYMatrix = TransformMatrix.createRotateYMatrix(Math.acos(rn.getY()));
		// this.rotateZMatrix = TransformMatrix.createRotateZMatrix(Math.acos(rn.getZ()));
		
		super.setLocation(this.state.x);
	}
	
	public State getState() { return this.state; }
}
