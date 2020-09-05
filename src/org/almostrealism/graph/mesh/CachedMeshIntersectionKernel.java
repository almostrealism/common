/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.math.KernelizedProducer;
import org.almostrealism.math.MemoryBank;
import org.almostrealism.util.DimensionAware;
import org.almostrealism.util.Producer;

public class CachedMeshIntersectionKernel implements KernelizedProducer<Scalar>, DimensionAware {
	private MeshData data;
	private KernelizedProducer<Ray> ray;

	private PairBank cache;

	private int width, height, ssw, ssh;

	public CachedMeshIntersectionKernel(MeshData data, KernelizedProducer<Ray> ray) {
		this.data = data;
		this.ray = ray;
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		this.width = width;
		this.height = height;
		this.ssw = ssw;
		this.ssh = ssh;
	}

	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryBank args[], int offset, int length) {
		if (destination instanceof ScalarBank == false) {
			throw new IllegalArgumentException("Kernel output is Scalar");
		}

		cache = new PairBank(destination.getCount());
		data.evaluateIntersectionKernel(ray, cache, args, offset, length);
		for (int i = 0; i < cache.getCount(); i++) {
			((ScalarBank) destination).get(i).setMem(new double[] { cache.get(i).getA(), 1.0 });
		}
	}

	@Override
	public Scalar evaluate(Object[] args) {
		return data.evaluateIntersection(ray, args);
	}

	public Producer<Vector> getClosestNormal() {
		return new Producer<Vector>() {
			@Override
			public Vector evaluate(Object[] args) {
				Pair pos = (Pair) args[0];
				int n = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);
				return data.get((int) cache.get(n).getB()).getNormal();
			}

			@Override
			public void compact() {

			}
		};
	}

	@Override
	public void compact() {
		ray.compact();
	}
}
