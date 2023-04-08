/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.computations.StaticVectorComputation;
import org.almostrealism.hardware.MemoryData;

// TODO  This should be a StaticCollectionComputation
public class ImmutableVector extends StaticVectorComputation implements TripleFunction<Triple, Vector> {
	public ImmutableVector() {
		super(new Data());
	}

	public ImmutableVector(double x, double y, double z) {
		super(new Data());
		setValue(new Vector(x, y, z));
	}

	public ImmutableVector(Vector v) {
		super(new Data());
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
		public void setMem(int offset, MemoryData src, int srcOffset, int length) {
			if (initialized)
				throw new RuntimeException("Vector is immutable");
			else
				super.setMem(offset, src, srcOffset, length);
		}
	}
}
