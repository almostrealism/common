/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Camera;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.time.Temporal;

import java.awt.*;
import java.util.ArrayList;

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
	/**
	 * Draws a 2D projection of this rigid body onto the specified {@link Graphics} context.
	 *
	 * @param c      the camera defining the view projection
	 * @param g      the graphics context to draw into
	 * @param ox     x-axis offset for the drawing origin
	 * @param oy     y-axis offset for the drawing origin
	 * @param scale  scale factor for the projection
	 */
	void draw(Camera c, Graphics g, double ox, double oy, double scale);

	/**
	 * Updates any visual or derived state of this rigid body to reflect the current physics state.
	 */
	void updateModel();

	/**
	 * Returns the physics state object for this rigid body.
	 *
	 * @return the {@link State} holding position, momentum, velocity, force, and torque
	 */
	State getState();
	
	/**
	 * Mutable physics state for one simulated rigid body.
	 * <p>
	 * All vectors use the same coordinate system and units as the containing simulation.
	 * Momentum vectors are maintained in parallel with velocity vectors so that impulses
	 * can be applied correctly without accumulating floating-point drift.
	 * </p>
	 */
	class State {
		/** {@code true} once {@link #init} has been called; prevents double-initialization. */
		private boolean inited = false;

		/** Listeners notified each time {@link #update(double)} advances the state. */
		protected ArrayList<Temporal> listeners;

		/** Coefficient of restitution (bounciness) for collision response. */
		public double e;

		/** Mass of the simulated body in simulation mass units. */
		public double mass;

		/** Moment of inertia matrix; transforms angular velocity to angular momentum. */
		public TransformMatrix in; // moment of inertia matrix

		/** Position in world space. */
		public Vector x;

		/** Orientation represented as a rotation vector. */
		public Vector r;

		/** Linear momentum (mass * velocity). */
		public Vector p;

		/** Angular momentum (inertia * angular velocity). */
		public Vector l;

		/** Linear velocity. */
		public Vector v;

		/** Angular velocity. */
		public Vector w;

		/** Net force currently applied to the body. */
		public Vector f;

		/** Net torque currently applied to the body. */
		public Vector t;
		
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
			
			for (Temporal t : listeners) t.tick().get().run();
		}
		
		/**
		 * Applies a linear impulse to the body by directly modifying its linear momentum.
		 * Linear velocity is immediately recomputed from the updated momentum.
		 *
		 * @param impulse  the impulse vector to add to linear momentum
		 */
		public void linearImpulse(Vector impulse) {
			System.out.print(this.toString() + ": " + impulse.toString() + " (" + this.p.toString() + " -->");
			
			this.p.addTo(impulse);
			this.v = this.p.divide(this.mass);
			
			System.out.println(this.p +") " + this.v);
		}
		
		/**
		 * Applies an angular impulse to the body by directly modifying its angular momentum.
		 * Angular velocity is immediately recomputed from the updated momentum.
		 *
		 * @param impulse  the angular impulse vector to add to angular momentum
		 */
		public void angularImpulse(Vector impulse) {
			this.l.addTo(impulse);
			this.w = this.in.getInverse().transformAsOffset(this.l);
		}
		
		/**
		 * Registers a listener that is notified each time the state is updated.
		 *
		 * @param l  the listener to add
		 */
		public void addUpdateListener(Temporal l) { this.listeners.add(l); }
		
		/**
		 * Sets the mass of this body and recomputes its linear and angular momentum
		 * to remain consistent with the current velocities.
		 *
		 * @param mass  the new mass value
		 */
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
		
		/**
		 * Sets the linear velocity and updates linear momentum accordingly.
		 *
		 * @param v  the new linear velocity vector
		 */
		public void setLinearVelocity(Vector v) {
			this.v = v;
			this.p = this.v.multiply(this.mass);
		}
		
		/**
		 * Sets the angular velocity and updates angular momentum accordingly.
		 *
		 * @param w  the new angular velocity vector
		 */
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
		
		/**
		 * Returns a string representation of this state showing position, momentum, velocity, and force.
		 *
		 * @return a formatted string of the form {@code {position momentum velocity force}}
		 */
		@Override
		public String toString() {
			return "{" + this.x + " " + this.p + " " + this.v + " " + this.f + "}";
		}
	}
}
