/*
 * Copyright 2018 Michael Murray
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
import io.almostrealism.code.Variable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.util.Producer;

public class ImmutableVector implements VectorProducer, TripleFunction<Vector> {
	private boolean initialized = false;
	private Vector value;

	public ImmutableVector() {
		this.value = new Vector() {
			protected void setMem(int offset, double[] source, int srcOffset, int length) {
				if (initialized)
					throw new RuntimeException("Vector is immutable");
				else
					super.setMem(offset, source, srcOffset, length);
			}

			protected void setMem(int offset, Vector src, int srcOffset, int length) {
				if (initialized)
					throw new RuntimeException("Vector is immutable");
				else
					super.setMem(offset, src, srcOffset, length);
			}
		};
	}

	public ImmutableVector(double x, double y, double z) {
		this.value = new Vector(x, y, z) {
			protected void setMem(int offset, double[] source, int srcOffset, int length) {
				if (initialized)
					throw new RuntimeException("Vector is immutable");
				else
					super.setMem(offset, source, srcOffset, length);
			}

			protected void setMem(int offset, Vector src, int srcOffset, int length) {
				if (initialized)
					throw new RuntimeException("Vector is immutable");
				else
					super.setMem(offset, src, srcOffset, length);
			}
		};

		initialized = true;
	}

	public ImmutableVector(Vector v) {
		this.value = new Vector() {
			protected void setMem(int offset, double[] source, int srcOffset, int length) {
				if (initialized)
					throw new RuntimeException("Vector is immutable");
				else
					super.setMem(offset, source, srcOffset, length);
			}

			protected void setMem(int offset, Vector src, int srcOffset, int length) {
				if (initialized)
					throw new RuntimeException("Vector is immutable");
				else
					super.setMem(offset, src, srcOffset, length);
			}
		};

		this.value.setTo(v);

		initialized = true;
	}

	@Override
	public Vector evaluate(Object[] args) {
		return value;
	}

	@Override
	public void compact() {

	}

	@Override
	public Vector operate(Triple in) {
		return value;
	}

	@Override
	public Scope<? extends Variable> getScope(NameProvider p) {
		return null;
	}

	public Vector getValue() { return (Vector) value.clone(); }

	public void setValue(Vector v) { value.setTo(v); initialized = true; }
}
