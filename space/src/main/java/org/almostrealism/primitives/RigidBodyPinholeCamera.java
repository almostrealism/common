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
 * @author  Michael Murray
 */
public class RigidBodyPinholeCamera implements Temporal {
	private final RigidBody.State model;
	private PinholeCamera c;
	
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
	
	public void forward(double d) {
		this.model.linearImpulse(model.getRotation().divide(model.getRotation().length()).multiply(d));
	}
	
	public void backward(double d) {
		this.model.linearImpulse(model.getRotation().divide(model.getRotation().length()).multiply(-d));
	}
	
	public void turnLeft() {
		// this.model.angularImpulse();
	}
	
	public void turnRight() {
		// this.model.angularImpulse();
	}
	
	public void jump(double d) { this.model.linearImpulse(new Vector(0.0, d, 0.0)); }
}