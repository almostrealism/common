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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.computations.VectorFromVectorBank;
import org.almostrealism.util.Evaluable;

import java.util.function.Supplier;

public class VectorFromTriangleData extends VectorFromVectorBank<TriangleData> {
	public static final int ABC = 0;
	public static final int DEF = 1;
	public static final int JKL = 2;
	public static final int NORMAL = 3;

	public VectorFromTriangleData(Supplier<Evaluable<? extends TriangleData>> triangle, int position) {
		super(triangle, position);
	}
}
