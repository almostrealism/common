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

package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.Triangle;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class TriangleTest {
	@Test
	public void test() {
		Triangle t = new Triangle(new Vector(1.0, 1.0, -1.0),
									new Vector(-1.0, 1.0, -1.0),
									new Vector(0.0, -1.0, -1.0));
		System.out.println(t.intersectAt(new StaticProducer(new Ray(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, -1.0)))).get(0).evaluate(new Object[0]));
	}
}
