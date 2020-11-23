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
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

import static org.almostrealism.util.Ops.*;

public class CrossProduct extends VectorFromScalars {
	public CrossProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		super(ops().y(a).multiply(ops().z(b))
						.subtract(ops().z(a).multiply(ops().y(b))),
				ops().z(a).multiply(ops().x(b))
						.subtract(ops().x(a).multiply(ops().z(b))),
				ops().x(a).multiply(ops().y(b))
						.subtract(ops().y(a).multiply(ops().x(b))));
	}
}
