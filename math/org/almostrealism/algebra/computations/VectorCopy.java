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
import org.almostrealism.util.Producer;

public class VectorCopy implements Producer<Vector> {
	private Producer<Vector> v;

	public VectorCopy(Producer<Vector> v) {
		this.v = v;
	}

	@Override
	public Vector evaluate(Object args[]) { return new Vector(v.evaluate(args)); }

	@Override
	public void compact() {
		v.compact();
		// TODO
	}
}
