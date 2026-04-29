/*
 * Copyright 2016 Michael Murray
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
package org.almostrealism.space;

import org.almostrealism.geometry.BasicGeometry;

import java.util.ArrayDeque;
import java.util.EmptyStackException;

/**
 * {@link GeometryStack} extends {@link BasicGeometry} to allow for convenient
 * summing of {@link BasicGeometry} location, size, scale, and rotation into a
 * single set of values.
 * 
 * @author  Michael Murray
 */
public class GeometryStack extends BasicGeometry {
	/** Stack of accumulated geometry transformations; {@code this} is always at the bottom. */
	private final ArrayDeque<BasicGeometry> stack;

	/** Constructs a new {@link GeometryStack} with {@code this} as the base entry. */
	public GeometryStack() {
		stack = new ArrayDeque<>();
		stack.addFirst(this);
	}

	/**
	 * Removes the most recently pushed {@link BasicGeometry} and subtracts its
	 * transform contribution from the accumulated state.
	 *
	 * @throws java.util.EmptyStackException if only the base entry remains
	 */
	public void pop() {
		if (stack.peekFirst() == this)
			throw new EmptyStackException();

		BasicGeometry g = stack.removeFirst();
		
		this.location = location.subtract(g.location);
		this.size = size / g.size;
		scale.setX(scale.getX() / g.scale.getX());
		scale.setY(scale.getY() / g.scale.getY());
		scale.setZ(scale.getZ() / g.scale.getZ());
		rotateX = rotateX - g.rotateX;
		rotateY = rotateY - g.rotateY;
		rotateZ = rotateZ - g.rotateZ;

		this.transformCurrent = false;
	}
	
	/**
	 * Pushes a {@link BasicGeometry} onto the stack and accumulates its transform
	 * (location, size, scale, rotation) into the current state.
	 *
	 * @param g the geometry whose transform should be accumulated
	 */
	public void push(BasicGeometry g) {
		this.stack.addFirst(g);
		
		this.location = location.add(g.location);
		this.size = size * g.size;
		scale.setX(scale.getX() * g.scale.getX());
		scale.setY(scale.getY() * g.scale.getY());
		scale.setZ(scale.getZ() * g.scale.getZ());
		rotateX = rotateX + g.rotateX;
		rotateY = rotateY + g.rotateY;
		rotateZ = rotateZ + g.rotateZ;

		this.transformCurrent = false;
	}
}
