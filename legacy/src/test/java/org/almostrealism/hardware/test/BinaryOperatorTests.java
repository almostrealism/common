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

package org.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.bool.GreaterThan;
import org.almostrealism.util.CodeFeatures;
import io.almostrealism.relation.DynamicProducer;
import org.junit.Test;

public class BinaryOperatorTests implements CodeFeatures {
	@Test
	public void greaterThan() {
		GreaterThan<Vector> operation = new GreaterThan(
				3, i -> Ray.blank(),
				scalar(2.0),
				scalar(3.0),
				vector(1.0, 2.0, 3.0),
				vector(4.0, 5.0, 6.0));

		System.out.println(operation.getFunctionDefinition());
	}
}
