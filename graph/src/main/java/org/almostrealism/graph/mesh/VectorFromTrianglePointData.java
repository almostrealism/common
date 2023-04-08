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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

@Deprecated
public class VectorFromTrianglePointData extends VectorFromVectorBank<TrianglePointData> {
	public static final int P1 = 0;
	public static final int P2 = 1;
	public static final int P3 = 2;

	public VectorFromTrianglePointData(Supplier<Evaluable<? extends PackedCollection<?>>> triangle, int position) {
		super((Supplier) triangle, position);
	}
}
