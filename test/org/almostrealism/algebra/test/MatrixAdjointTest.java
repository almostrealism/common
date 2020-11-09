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

import org.almostrealism.algebra.computations.MatrixAdjoint;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.util.Provider;
import org.junit.Test;

public class MatrixAdjointTest {
	@Test
	public void test() {
//		System.out.println(new TransformMatrix().toString());
		new MatrixAdjoint(new Provider<>(new TransformMatrix())).evaluate(new Object[0]);
	}
}
