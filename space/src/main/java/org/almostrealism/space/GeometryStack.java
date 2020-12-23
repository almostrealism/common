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

import java.util.EmptyStackException;
import java.util.Stack;

import org.almostrealism.geometry.BasicGeometry;

/**
 * {@link GeometryStack} extends {@link BasicGeometry} to allow for convenient
 * summing of {@link BasicGeometry} location, size, scale, and rotation into a
 * single set of values.
 * 
 * @author  Michael Murray
 */
public class GeometryStack extends BasicGeometry {
	private Stack<BasicGeometry> stack;
	
	public GeometryStack() {
		stack = new Stack<>();
		stack.push(this);
	}
	
	public void pop() {
		if (stack.peek() == this)
			throw new EmptyStackException();
		
		BasicGeometry g = stack.pop();
		
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
	
	public void push(BasicGeometry g) {
		this.stack.push(g);
		
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
