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

package org.almostrealism.physics;

import java.awt.Graphics;
import java.util.ArrayList;

import org.almostrealism.algebra.Camera;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.time.Temporal;


/**
 * The RigidBody class stores state information for a simulated object.
 * This includes location, rotation, linear and angular momentum and the corresponding
 * rates, linear and angular velocity, force, and torque.
 * 
 * @author  Michael Murray
 */
public interface RigidBody {
	/**
	 * Performs intersection calculations for this RigidBody object and the specified RigidBody object.
	 * 
	 * @return {intersection point, normal to intersection} or a zero length array if there is no intersection.
	 */
	Vector[] intersect(RigidBody b);
	void draw(Camera c, Graphics g, double ox, double oy, double scale);

	void updateModel();
	
	State getState();
	
	class State {
		private boolean inited = false;
		private double elapsed = -1.0;
		
		protected ArrayList<Temporal> listeners;
		
		public double e;

		public double mass;        // mass value
		public TransformMatrix in; // moment of inertia matrix

		public Vector x, r; // location and rotation
		public Vector p, l; // linear and angular momentum

		public Vector v, w; // linear and angular velocity
		public Vector f, t; // force and torque
		
		/**
		 * Initializes this RigidBody object with the specified location, rotation,
		 * linear and angular velocity, force, and torque.
		 * 
		 * @param x  location
		 * @param r  rotation
		 * @param v  linear velocity
		 * @param w  angular velocity
		 * @param f  force
		 * @param t  torque
		 * @param mass  total mass of the simulated rigid body
		 * @param inertia  moment of inertia matrix
		 * @param e  coefficient of restitution
		 */
		public void init(Vector x, Vector r, Vector v, Vector w, Vector f, Vector t,
				double mass, TransformMatrix inertia, double e) {
			if (this.inited) throw new RuntimeException("Rigid body state has already been initialized.");
			
			this.listeners = new ArrayList<>();
			
			this.elapsed = 0.0;
			
			this.e = e;
			
			this.mass = mass;
			this.in = inertia;
			
			this.x = x;
			this.r = r;
			
			this.v = v;
			this.w = w;
			this.f = f;
			this.t = t;
			
			this.p = this.v.multiply(this.mass);
			this.l = this.in.transformAsOffset(this.w);
			
			this.inited = true;
		}
		
		/**
		 * Updates the state variables stored by this RigidBody object for the given time step.
		 * 
		 * @param time  Time increment
		 */
		public void update(double time) {
			this.x.addTo(this.v.multiply(time));
			this.r.addTo(this.in.getInverse().transformAsOffset(this.l).crossProduct(this.r));
			
			this.p.addTo(this.f.multiply(time));
			this.l.addTo(this.t.multiply(time));
			
			this.v = this.p.divide(this.mass);
			this.w = this.in.getInverse().transformAsOffset(this.l);
			
			for (Temporal t : listeners) t.tick().run();
		}
		
		public void linearImpulse(Vector impulse) {
			System.out.print(this.toString() + ": " + impulse.toString() + " (" + this.p.toString() + " -->");
			
			this.p.addTo(impulse);
			this.v = this.p.divide(this.mass);
			
			System.out.println(this.p +") " + this.v);
		}
		
		public void angularImpulse(Vector impulse) {
			this.l.addTo(impulse);
			this.w = this.in.getInverse().transformAsOffset(this.l);
		}
		
		public void addUpdateListener(Temporal l) { this.listeners.add(l); }
		
		public void setMass(double mass) {
			this.mass = mass;
			this.p = this.v.multiply(this.mass);
			this.l = this.w.multiply(this.mass);
		}
		
		public double getMass() { return this.mass; }
		public double getRestitution() { return this.e; }
		public TransformMatrix getInertia() { return this.in; }
		
		public void setLocation(Vector x) { this.x = x; }
		public void setRotation(Vector r) { this.r = r; }
		
		public void setLinearVelocity(Vector v) {
			this.v = v;
			this.p = this.v.multiply(this.mass);
		}
		
		public void setAngularVelocity(Vector w) {
			this.w = w;
			this.l = this.w.multiply(this.mass);
		}
		
		public void setForce(Vector f) { this.f = f; }
		public void setTorque(Vector t) { this.t = t; }
		
		public Vector getLocation() { return this.x; }
		public Vector getRotation() { return this.r; }
		public Vector getLinearMomentum() { return this.p; }
		public Vector getAngularMomentum() { return this.l; }
		public Vector getLinearVelocity() { return this.v; }
		public Vector getAngularVelocity() { return this.w; }
		public Vector getForce() { return this.f; }
		public Vector getTorque() { return this.t; }
		
		public String toString() {
			return "{" + this.x + " " + this.p + " " + this.v + " " + this.f + "}";
		}
	}
}
