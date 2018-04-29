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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class VectorProduct extends VectorFutureAdapter {
	public VectorProduct() { }
	public VectorProduct(VectorProducer... producers) { addAll(convertToFutures(producers)); }
	public VectorProduct(Future<VectorProducer>... producers) { addAll(Arrays.asList(producers)); }

	@Override
	public Vector evaluate(Object[] args) {
		Vector v = new Vector(1.0, 1.0, 1.0);

		for (Future<VectorProducer> c : this) {
			try {
				Vector v2 = c.get().evaluate(args);
				v.setX(v.getX() * v2.getX());
				v.setY(v.getY() * v2.getY());
				v.setZ(v.getZ() * v2.getZ());
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}

		return v;
	}

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		throw new RuntimeException("getScope is not implemented");
	}

	// TODO  Combine ColorProducts that are equal by converting to ColorPow
	@Override
	public void compact() {
		super.compact();

		List<Future<VectorProducer>> p = new ArrayList<>();
		List<VectorFutureAdapter.StaticVectorProducer> replaced = new ArrayList<>();

		for (VectorProducer c : getStaticVectorProducers()) {
			if (c instanceof VectorProduct) {
				replaced.add(new VectorFutureAdapter.StaticVectorProducer(c));

				for (Future<VectorProducer> cp : ((VectorProduct) c)) {
					p.add(cp);
				}
			}
		}

		for (VectorFutureAdapter.StaticVectorProducer s : replaced) {
			remove(replaced);
		}

		addAll(p);
	}
}
