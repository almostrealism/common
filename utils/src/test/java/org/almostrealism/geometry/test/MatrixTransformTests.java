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

import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MatrixTransformTests implements TestFeatures {
	@Test(timeout = 10000)
	public void scaleTranslateThenTransform() {
		scaleAndTranslate();
		transformAsLocation1();
	}

	@Test(timeout = 10000)
	public void transformAsLocation1() {
		TransformMatrix matrix = new TransformMatrix(translationMatrix(vector(0.0, 10.0, 0.0)).evaluate(), 0);
		Vector v = new Vector(transformAsLocation(matrix, vector(1.0, 2.0, 3.0)).evaluate(), 0);

		assertEquals(1.0, v.toDouble(0));
		assertEquals(12.0, v.toDouble(1));
		assertEquals(3.0, v.toDouble(2));
	}

	@Test(timeout = 10000)
	public void transformAsLocation2() {
		TransformMatrix matrix = new TransformMatrix(scaleMatrix(vector(2.0, 1.0, 3.0)).evaluate(), 0);
		Vector v = new Vector(transformAsLocation(matrix, vector(1.0, 2.0, 3.0)).evaluate(), 0);

		assertEquals(2.0, v.toDouble(0));
		assertEquals(2.0, v.toDouble(1));
		assertEquals(9.0, v.toDouble(2));
	}

	@Test(timeout = 10000)
	public void transformAsOffset() {
		TransformMatrix matrix = new TransformMatrix(translationMatrix(vector(0.0, 10.0, 0.0)).evaluate(), 0);
		Vector v = new Vector(transformAsOffset(matrix, vector(1.0, 2.0, 3.0)).evaluate(), 0);
		assertEquals(1.0, v.toDouble(0));
		assertEquals(2.0, v.toDouble(1));
		assertEquals(3.0, v.toDouble(2));

		matrix = new TransformMatrix(scaleMatrix(vector(2.0, 1.0, 3.0)).evaluate(), 0);
		v = new Vector(transformAsOffset(matrix, vector(1.0, 2.0, 3.0)).evaluate(), 0);
		assertEquals(2.0, v.toDouble(0));
		assertEquals(2.0, v.toDouble(1));
		assertEquals(9.0, v.toDouble(2));
	}

	@Test(timeout = 10000)
	public void applyInverse() {
		TransformMatrix m = new TransformMatrix(translationMatrix(vector(0.0, -10.0, 0.0)).evaluate(), 0);

		Ray r = new Ray(new Vector(1.0, 2.0, 3.0), new Vector(4.0, 5.0, 6.0));
		r = new Ray(transform(m.getInverse(), v(r)).evaluate(), 0);
		r.print();

		assertEquals(1.0, r.getOrigin().toDouble(0));
		assertEquals(12.0, r.getOrigin().toDouble(1));
		assertEquals(3.0, r.getOrigin().toDouble(2));
		assertEquals(4.0, r.getDirection().toDouble(0));
		assertEquals(5.0, r.getDirection().toDouble(1));
		assertEquals(6.0, r.getDirection().toDouble(2));
	}

	@Test(timeout = 10000)
	public void scaleAndTranslate() {
		TransformMatrix matrix = new TransformMatrix(new double[][] {
				{ 0.25, 0.0,  0.0,   0.0 },
				{ 0.0,  0.25, 0.0,   3.4 },
				{ 0.0,  0.0,  0.25, -3.0 },
				{ 0.0,  0.0,  0.0,   1.0 }
		});

		CollectionProducer transform = transform(matrix,
				ray(1.0, 2.0, 3.0,4.0, 5.0, 6.0));
		Ray r = new Ray(transform.evaluate(), 0);
		log(r);

		assertEquals(0.25, r.getOrigin().toDouble(0));
		assertEquals(3.9, r.getOrigin().toDouble(1));
		assertEquals(-2.25, r.getOrigin().toDouble(2));
		assertEquals(1.0, r.getDirection().toDouble(0));
		assertEquals(1.25, r.getDirection().toDouble(1));
		assertEquals(1.5, r.getDirection().toDouble(2));
	}
}
