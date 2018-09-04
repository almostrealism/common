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

package org.almostrealism.relation.test;

import org.almostrealism.algebra.RayMatrixTransform;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.math.AcceleratedProducer;
import org.almostrealism.math.Hardware;
import org.almostrealism.relation.GeneratedOperatorMap;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class GeneratedOperatorMapTest {
	@Test
	public void rayMatrixTransformTest() {
		Ray r = new Ray(new Vector(1.0, 2.0, 4.0), new Vector(6.0, 3.0, 1.0));
		AcceleratedProducer a = new RayMatrixTransform(new TransformMatrix(), new StaticProducer<>(r));
		GeneratedOperatorMap m = new GeneratedOperatorMap(Hardware.getLocalHardware(), a.getOperator());
	}
}
