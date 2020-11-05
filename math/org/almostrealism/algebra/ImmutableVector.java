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

package org.almostrealism.algebra;

import io.almostrealism.code.Scope;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.util.AcceleratedStaticVectorComputation;

public class ImmutableVector extends AcceleratedStaticVectorComputation implements TripleFunction<Triple, Vector> {
	public ImmutableVector() {
		super(new Data(), Vector.blank());
	}

	public ImmutableVector(double x, double y, double z) {
		super(new Data(), Vector.blank());
		setValue(new Vector(x, y, z));
	}

	public ImmutableVector(Vector v) {
		super(new Data(), Vector.blank());
		setValue(v);
	}

	@Override
	public Vector operate(Triple in) { return getValue(); }

	@Override
	public Vector getValue() { return (Vector) super.getValue().clone(); }

	public void setValue(Vector v) { super.getValue().setTo(v); ((Data) super.getValue()).initialized = true; }

	private static class Data extends Vector {
		private boolean initialized;

		@Override
		public void setMem(int offset, double[] source, int srcOffset, int length) {
			if (initialized)
				throw new RuntimeException("Vector is immutable");
			else
				super.setMem(offset, source, srcOffset, length);
		}

		@Override
		public void setMem(int offset, MemWrapper src, int srcOffset, int length) {
			if (initialized)
				throw new RuntimeException("Vector is immutable");
			else
				super.setMem(offset, src, srcOffset, length);
		}
	}
}
