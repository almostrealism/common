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

package org.almostrealism.space;

import io.almostrealism.code.OperationAdapter;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.geometry.DimensionAware;
import io.almostrealism.relation.Evaluable;

public class CachedMeshIntersectionKernel implements KernelizedEvaluable<Scalar>, DimensionAware {
	private MeshData data;
	private KernelizedEvaluable<Ray> ray;

	private PairBank cache;

	private int width = -1, height = -1, ssw = -1, ssh = -1;

	public CachedMeshIntersectionKernel(MeshData data, KernelizedProducer<Ray> ray) {
		this.data = data;
		this.ray = ray.get();
		((OperationAdapter) this.ray).compile();
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		this.width = width;
		this.height = height;
		this.ssw = ssw;
		this.ssh = ssh;
	}

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) { return new ScalarBank(size); }

	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryBank args[]) {
		if (destination instanceof ScalarBank == false) {
			throw new IllegalArgumentException("Kernel output is Scalar, destination must be ScalarBank");
		}

		cache = new PairBank(destination.getCount());
		data.evaluateIntersectionKernel(ray, cache, args);
		for (int i = 0; i < cache.getCount(); i++) {
			((ScalarBank) destination).get(i).setMem(new double[] { cache.get(i).getA(), 1.0 });
		}
	}

	/**
	 * Returns a cached value from {@link #kernelEvaluate(MemoryBank, MemoryBank[])}.
	 * This method will not work properly unless the kernel has already been evaluated.
	 */
	@Override
	public Scalar evaluate(Object[] args) {
		if (cache == null) {
			return new Scalar(data.evaluateIntersection(ray, args).getA());
		} else {
			Pair pos = (Pair) args[0];
			int n = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);
			return new Scalar(cache.get(n).getA());
		}
	}

	public Evaluable<Vector> getClosestNormal() {
		return new KernelizedEvaluable<Vector>() {
			@Override
			public Vector evaluate(Object[] args) {
				if (cache == null) {
					return data.get((int) data.evaluateIntersection(ray, args).getB()).getNormal();
				} else {
					Pair pos = (Pair) args[0];
					int n = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);
					if (n < 0) return ZeroVector.getEvaluable().evaluate();
					int a = (int) cache.get(n).getB();
					if (a < 0) return ZeroVector.getEvaluable().evaluate();
					return data.get(a).getNormal();
				}
			}

			@Override
			public MemoryBank<Vector> createKernelDestination(int size) { return new VectorBank(size); }
		};
	}
}
