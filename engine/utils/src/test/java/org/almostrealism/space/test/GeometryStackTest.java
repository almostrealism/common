/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.space.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.BasicGeometry;
import org.almostrealism.space.GeometryStack;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.EmptyStackException;

/**
 * Behavioral tests for {@link GeometryStack}, verifying that pushing accumulates a
 * geometry's transform and popping reverses it.
 */
public class GeometryStackTest extends TestSuiteBase {

	/**
	 * Builds a geometry with a distinctive location, size, scale, and rotation.
	 *
	 * @return the configured geometry
	 */
	private BasicGeometry sample() {
		BasicGeometry g = new BasicGeometry();
		g.setLocation(new Vector(1.0, 2.0, 3.0));
		g.setSize(2.0);
		g.setScaleCoefficients(2.0, 3.0, 4.0);
		g.setRotationCoefficients(0.1, 0.2, 0.3);
		return g;
	}

	/** A fresh stack starts at the origin with identity scale and no rotation. */
	@Test(timeout = 10000)
	public void identityInitialState() {
		GeometryStack stack = new GeometryStack();

		Assert.assertEquals(0.0, stack.getLocation().getX(), 1e-12);
		Assert.assertEquals(1.0, stack.getSize(), 1e-12);
		Assert.assertEquals(1.0, stack.getScaleCoefficients()[0], 1e-12);
		Assert.assertEquals(0.0, stack.rotateX, 1e-12);
	}

	/** Pushing a geometry adds its location and rotation and multiplies its size and scale. */
	@Test(timeout = 10000)
	public void pushAccumulatesTransform() {
		GeometryStack stack = new GeometryStack();
		stack.push(sample());

		Assert.assertEquals(1.0, stack.getLocation().getX(), 1e-12);
		Assert.assertEquals(2.0, stack.getLocation().getY(), 1e-12);
		Assert.assertEquals(3.0, stack.getLocation().getZ(), 1e-12);
		Assert.assertEquals(2.0, stack.getSize(), 1e-12);
		Assert.assertEquals(2.0, stack.getScaleCoefficients()[0], 1e-12);
		Assert.assertEquals(3.0, stack.getScaleCoefficients()[1], 1e-12);
		Assert.assertEquals(4.0, stack.getScaleCoefficients()[2], 1e-12);
		Assert.assertEquals(0.1, stack.rotateX, 1e-12);
		Assert.assertEquals(0.2, stack.rotateY, 1e-12);
		Assert.assertEquals(0.3, stack.rotateZ, 1e-12);
	}

	/** Pushing then popping restores the identity state exactly. */
	@Test(timeout = 10000)
	public void popReversesPush() {
		GeometryStack stack = new GeometryStack();
		stack.push(sample());
		stack.pop();

		Assert.assertEquals(0.0, stack.getLocation().getX(), 1e-12);
		Assert.assertEquals(0.0, stack.getLocation().getY(), 1e-12);
		Assert.assertEquals(0.0, stack.getLocation().getZ(), 1e-12);
		Assert.assertEquals(1.0, stack.getSize(), 1e-12);
		Assert.assertEquals(1.0, stack.getScaleCoefficients()[0], 1e-12);
		Assert.assertEquals(1.0, stack.getScaleCoefficients()[1], 1e-12);
		Assert.assertEquals(1.0, stack.getScaleCoefficients()[2], 1e-12);
		Assert.assertEquals(0.0, stack.rotateX, 1e-12);
		Assert.assertEquals(0.0, stack.rotateY, 1e-12);
		Assert.assertEquals(0.0, stack.rotateZ, 1e-12);
	}

	/** Two pushes accumulate additively/multiplicatively before a matching pop. */
	@Test(timeout = 10000)
	public void nestedPushPop() {
		GeometryStack stack = new GeometryStack();
		stack.push(sample());
		stack.push(sample());

		Assert.assertEquals(2.0, stack.getLocation().getX(), 1e-12);
		Assert.assertEquals(4.0, stack.getSize(), 1e-12);
		Assert.assertEquals(4.0, stack.getScaleCoefficients()[0], 1e-12);
		Assert.assertEquals(0.2, stack.rotateX, 1e-12);

		stack.pop();
		Assert.assertEquals(1.0, stack.getLocation().getX(), 1e-12);
		Assert.assertEquals(2.0, stack.getSize(), 1e-12);
	}

	/** Popping the base entry is not allowed. */
	@Test(timeout = 10000, expected = EmptyStackException.class)
	public void popBaseEntryThrows() {
		GeometryStack stack = new GeometryStack();
		stack.pop();
	}
}
