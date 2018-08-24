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

public class VectorMathTest {
	public static void main(String args[]) {
		Vector v = new Vector(1, 2, 3);
		System.out.println(v);
		v.subtractFrom(new Vector(2, 2, 2));
//		System.out.println(v.dotProduct(new Vector(1, 1, 1)));
		System.out.println(v);

		System.out.println(new Vector(2.0, 2.0, 2.0).divide(2.0));
	}
}
