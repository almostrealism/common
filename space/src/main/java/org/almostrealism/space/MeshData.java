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

package org.almostrealism.space;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.KernelizedOperation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;

public class MeshData extends PackedCollection<PackedCollection<?>> {
	/**
	 * If there is not enough RAM to run the entire kernel at once,
	 * it can be run one {@link Ray} at a time by enabling this flag.
	 */
	public static boolean enablePartialKernel = true;

	private ScalarBank distances;

	public MeshData(int triangles) {
		super(new TraversalPolicy(triangles, 4, 3), 1, delegateSpec ->
				new PackedCollection<>(new TraversalPolicy(4, 3), 1, delegateSpec.getDelegate(), delegateSpec.getOffset()));
		distances = new ScalarBank(getCount());
	}

	public synchronized Pair evaluateIntersection(Evaluable<Ray> ray, Object args[]) {
		PackedCollection<Ray> in = Ray.bank(1);
		PackedCollection<Pair<?>> out = Pair.bank(1);

		PackedCollection<Pair<?>> conf = Pair.bank(1);
		conf.set(0, new Pair(getCount(), Intersection.e));

		in.set(0, ray.evaluate(args));
//		Triangle.intersectAt.kernelEvaluate(distances, new MemoryBank[] { in, this });
//		RankedChoiceEvaluable.highestRank.kernelEvaluate(out, new MemoryBank[] { distances, conf });
		Triangle.intersectAt.into(distances).evaluate(in, this);
		RankedChoiceEvaluable.highestRank.into(out).evaluate(distances, conf);
		return out.get(0);
	}

	public void evaluateIntersectionKernel(KernelizedEvaluable<Ray> ray, ScalarBank destination, MemoryData args[]) {
		PackedCollection<Pair<?>> result = Pair.bank(destination.getCount());
		evaluateIntersectionKernel(ray, result, args);
		for (int i = 0; i < result.getCount(); i++) {
			destination.get(i).setMem(new double[] { result.get(i).getA(), 1.0 });
		}
	}

	public void evaluateIntersectionKernel(KernelizedEvaluable<Ray> ray, PackedCollection<Pair<?>> destination, MemoryData args[]) {
		long startTime = System.currentTimeMillis();
		PackedCollection<Ray> rays = Ray.bank(destination.getCount());
		ray.kernelEvaluate(rays, args);

		if (KernelizedOperation.enableKernelLog) System.out.println("MeshData: Evaluated ray kernel in " + (System.currentTimeMillis() - startTime) + " msec");

		PackedCollection<Pair<?>> dim = Pair.bank(1);
		dim.set(0, new Pair(this.getCount(), rays.getCount()));

		if (enablePartialKernel) {
			ScalarBank distances = new ScalarBank(getCount());
			PackedCollection<Ray> in = Ray.bank(1);
			PackedCollection<Pair<?>> out = Pair.bank(1);

			PackedCollection<Pair<?>> conf = Pair.bank(1);
			conf.set(0, new Pair(getCount(), Intersection.e));

			for (int i = 0; i < rays.getCount(); i++) {
				in.set(0, rays.get(i));
				Triangle.intersectAt.kernelEvaluate(distances, new MemoryBank[] { in, this, dim });
				RankedChoiceEvaluable.highestRank.kernelEvaluate(out, new MemoryBank[] { distances, conf });
				destination.set(i, out.get(0));
			}

			if (KernelizedOperation.enableKernelLog) System.out.println(rays.getCount() + " intersection kernels evaluated");
		} else {
			ScalarBank distances = new ScalarBank(this.getCount() * rays.getCount());

			startTime = System.currentTimeMillis();
			Triangle.intersectAt.kernelEvaluate(distances, new MemoryBank[] { rays, this, dim });
			System.out.println("MeshData: Completed intersection kernel in " +
					(System.currentTimeMillis() - startTime) + " msec");
			// TODO Choose best with highestRank kernel
		}
	}
}
