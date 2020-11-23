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

package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFromVectors;
import org.almostrealism.geometry.TransformAsLocation;
import org.almostrealism.geometry.TransformAsOffset;
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class RayMatrixTransform extends RayFromVectors {
	public RayMatrixTransform(TransformMatrix t, Supplier<Evaluable<? extends Ray>> r) {
		super(new TransformAsLocation(t, new RayOrigin(r)),
				new TransformAsOffset(t, new RayDirection(r)));
	}
}
