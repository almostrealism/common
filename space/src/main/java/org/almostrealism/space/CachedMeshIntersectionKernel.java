/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.geometry.DimensionAware;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.stream.Stream;

public class CachedMeshIntersectionKernel implements KernelizedEvaluable<Scalar>, DimensionAware {
	private MeshData data;
	private Evaluable<Ray> ray;
	private Evaluable<Vector> closestNormal;

	private PackedCollection<Pair<?>> cache;

	private int width = -1, height = -1, ssw = -1, ssh = -1;

	public CachedMeshIntersectionKernel(MeshData data, Producer<Ray> ray) {
		this.data = data;
		this.ray = ray.get();
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		this.width = width;
		this.height = height;
		this.ssw = ssw;
		this.ssh = ssh;
	}

	@Override
	public MemoryBank<Scalar> createDestination(int size) { return Scalar.scalarBank(size); }

	@Override
	public Evaluable withDestination(MemoryBank destination) {
		return args -> {
			cache = Pair.bank(destination.getCount());
			data.evaluateIntersectionKernel(ray, cache, Stream.of(args).map(MemoryData.class::cast).toArray(MemoryData[]::new));
			for (int i = 0; i < cache.getCountLong(); i++) {
				((MemoryData) destination.get(i)).setMem(cache.get(i).getA(), 1.0);
			}

			return destination;
		};
	}

	/**
	 * Returns a cached value from kernel evaluation.
	 * This method will not work properly unless kernel
	 * evaluation has already taken place.
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
		if (closestNormal == null) {
			closestNormal = new HardwareEvaluable<>(() -> args -> {
				if (cache == null) {
					return new Vector(data.get((int) data.evaluateIntersection(ray, args).getB()).get(4), 0);
				} else {
					Pair pos = (Pair) args[0];
					int n = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);
					if (n < 0) return ZeroVector.getEvaluable().evaluate();
					int a = (int) cache.get(n).getB();
					if (a < 0) return ZeroVector.getEvaluable().evaluate();
					return new Vector(data.get(a).get(4), 0);
				}
			}, new MemoryDataDestination<>(Vector::bank), null, true);
		}

		return closestNormal;
	}
}
