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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.Producer;

public class CrossProduct extends VectorFromScalars {
	public CrossProduct(Producer<Vector> a, Producer<Vector> b) {
		super(VectorProducer.y(a).multiply(VectorProducer.z(b))
						.subtract(VectorProducer.z(a)).multiply(VectorProducer.y(b)),
				VectorProducer.z(a).multiply(VectorProducer.x(b))
						.subtract(VectorProducer.x(a)).multiply(VectorProducer.z(b)),
				VectorProducer.x(a).multiply(VectorProducer.y(b))
						.subtract(VectorProducer.y(a)).multiply(VectorProducer.x(b)));
	}
}
