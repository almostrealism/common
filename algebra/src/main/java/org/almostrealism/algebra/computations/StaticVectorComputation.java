/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.algebra.computations;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ReshapeProducer;

@Deprecated
public class StaticVectorComputation extends StaticComputationAdapter<Vector> implements VectorProducer, Shape<Producer<PackedCollection<?>>> {
	public StaticVectorComputation(Vector value) {
		super(value, Vector.blank(), Vector::bank);
	}

	@Override
	public TraversalPolicy getShape() {
		return new TraversalPolicy(3);
	}

	@Override
	public Producer<PackedCollection<?>> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}
}
