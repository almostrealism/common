/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.geometry.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MatrixTransformTests implements TestFeatures {
	@Test
	public void scaleTranslateThenTransform() {
		scaleAndTranslate();
		transformAsLocation1();
	}

	@Test
	public void transformAsLocation1() {
		TransformMatrix matrix = translationMatrix(vector(0.0, 10.0, 0.0)).evaluate();
		Vector v = transformAsLocation(matrix, vector(1.0, 2.0, 3.0)).evaluate();

		assertEquals(1.0, v.getX());
		assertEquals(12.0, v.getY());
		assertEquals(3.0, v.getZ());
	}

	@Test
	public void transformAsLocation2() {
		TransformMatrix matrix = scaleMatrix(vector(2.0, 1.0, 3.0)).evaluate();
		Vector v = transformAsLocation(matrix, vector(1.0, 2.0, 3.0)).evaluate();

		assertEquals(2.0, v.getX());
		assertEquals(2.0, v.getY());
		assertEquals(9.0, v.getZ());
	}

	@Test
	public void transformAsOffset() {
		TransformMatrix matrix = translationMatrix(vector(0.0, 10.0, 0.0)).evaluate();
		Vector v = transformAsOffset(matrix, vector(1.0, 2.0, 3.0)).evaluate();
		assertEquals(1.0, v.getX());
		assertEquals(2.0, v.getY());
		assertEquals(3.0, v.getZ());

		matrix = scaleMatrix(vector(2.0, 1.0, 3.0)).evaluate();
		v = transformAsOffset(matrix, vector(1.0, 2.0, 3.0)).evaluate();
		assertEquals(2.0, v.getX());
		assertEquals(2.0, v.getY());
		assertEquals(9.0, v.getZ());
	}

	@Test
	public void applyInverse() {
		TransformMatrix m = translationMatrix(vector(0.0, -10.0, 0.0)).evaluate();

		Ray r = new Ray(new Vector(1.0, 2.0, 3.0), new Vector(4.0, 5.0, 6.0));
		r = new Ray(transform(m.getInverse(), v(r)).evaluate(), 0);
		r.print();

		assertEquals(1.0, r.getOrigin().getX());
		assertEquals(12.0, r.getOrigin().getY());
		assertEquals(3.0, r.getOrigin().getZ());
		assertEquals(4.0, r.getDirection().getX());
		assertEquals(5.0, r.getDirection().getY());
		assertEquals(6.0, r.getDirection().getZ());
	}

	@Test
	public void scaleAndTranslate() {
		TransformMatrix matrix = new TransformMatrix(new double[][] {
				{ 0.25, 0.0,  0.0,   0.0 },
				{ 0.0,  0.25, 0.0,   3.4 },
				{ 0.0,  0.0,  0.25, -3.0 },
				{ 0.0,  0.0,  0.0,   1.0 }
		});

		CollectionProducer transform = transform(matrix,
				ray(1.0, 2.0, 3.0,4.0, 5.0, 6.0));
		Ray r = new Ray((PackedCollection) transform.evaluate(), 0);
		System.out.println(r);

		assertEquals(0.25, r.getOrigin().getX());
		assertEquals(3.9, r.getOrigin().getY());
		assertEquals(-2.25, r.getOrigin().getZ());
		assertEquals(1.0, r.getDirection().getX());
		assertEquals(1.25, r.getDirection().getY());
		assertEquals(1.5, r.getDirection().getZ());
	}
}
