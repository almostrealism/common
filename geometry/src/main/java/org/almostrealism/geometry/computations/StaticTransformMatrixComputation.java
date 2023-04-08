/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.geometry.computations;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.StaticComputationAdapter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.TransformMatrixProducer;

@Deprecated
public class StaticTransformMatrixComputation extends StaticComputationAdapter<TransformMatrix> implements TransformMatrixProducer,
		Shape<Producer<PackedCollection<?>>> {
	public StaticTransformMatrixComputation(TransformMatrix value) {
		super(value, TransformMatrix.blank(), i -> new PackedCollection<>(i, 16));
	}

	@Override
	public TraversalPolicy getShape() {
		return new TraversalPolicy(16);
	}

	@Override
	public Producer<PackedCollection<?>> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}
}
