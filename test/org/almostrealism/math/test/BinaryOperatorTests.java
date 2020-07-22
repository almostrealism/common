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

package org.almostrealism.math.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.math.bool.GreaterThan;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class BinaryOperatorTests {
	@Test
	public void greaterThan() {
		GreaterThan<Vector> operation = new GreaterThan(
				3, DynamicProducer.forMemLength(),
				StaticProducer.of(new Scalar(2.0)),
				StaticProducer.of(new Scalar(3.0)),
				StaticProducer.of(new Vector(1.0, 2.0, 3.0)),
				StaticProducer.of(new Vector(4.0, 5.0, 6.0)));

		System.out.println(operation.getFunctionDefinition());
	}
}
