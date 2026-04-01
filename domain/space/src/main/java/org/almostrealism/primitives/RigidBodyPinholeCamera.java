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
import org.almostrealism.physics.RigidBody;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

/**
 * A pinhole camera that tracks the position and orientation of a {@link RigidBody}
 * simulation model, updating a {@link PinholeCamera} each tick.
 *
 * @author  Michael Murray
 */
public class RigidBodyPinholeCamera implements Temporal {
	/** The rigid-body simulation state that drives the camera pose. */
	private final RigidBody.State model;

	/** The pinhole camera whose position and direction are updated each tick. */
	private PinholeCamera c;
	
	/**
	 * Constructs a {@link RigidBodyPinholeCamera} that mirrors the pose of the given rigid body.
	 *
	 * @param model the rigid body whose state drives the camera
	 * @param c     the pinhole camera to update
	 */
	public RigidBodyPinholeCamera(RigidBody model, PinholeCamera c) {
		c.setLocation(model.getState().getLocation());
		c.setViewingDirection(model.getState().getRotation());
		c.setUpDirection(new Vector(0.0, 1.0, 0.0));
		
		this.model = model.getState();
		this.model.addUpdateListener(this);
	}

	@Override
	public Supplier<Runnable> tick() {
		return () -> () -> {
			c.setLocation(this.model.getLocation());
			c.setViewingDirection(this.model.getRotation());
		};
	}
	
	/**
	 * Applies a linear impulse in the camera's current viewing direction (forward).
	 *
	 * @param d the magnitude of the impulse
	 */
	public void forward(double d) {
		this.model.linearImpulse(model.getRotation().divide(model.getRotation().length()).multiply(d));
	}
	
	/**
	 * Applies a linear impulse opposite to the camera's current viewing direction (backward).
	 *
	 * @param d the magnitude of the impulse
	 */
	public void backward(double d) {
		this.model.linearImpulse(model.getRotation().divide(model.getRotation().length()).multiply(-d));
	}
	
	/** Rotates the camera to the left (not yet implemented). */
	public void turnLeft() {
		// this.model.angularImpulse();
	}
	
	/** Rotates the camera to the right (not yet implemented). */
	public void turnRight() {
		// this.model.angularImpulse();
	}
	
	/**
	 * Applies a vertical (Y-axis) linear impulse to make the body jump.
	 *
	 * @param d the magnitude of the upward impulse
	 */
	public void jump(double d) { this.model.linearImpulse(new Vector(0.0, d, 0.0)); }
}