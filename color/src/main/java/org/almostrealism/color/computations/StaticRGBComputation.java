/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.color.computations;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBProducer;
import org.almostrealism.algebra.computations.StaticComputationAdapter;

public class StaticRGBComputation extends StaticComputationAdapter<RGB> implements RGBProducer, Shape<Producer<PackedCollection<?>>> {
	public StaticRGBComputation(RGB value) {
		super(value, RGB.blank(), RGB::bank);
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
