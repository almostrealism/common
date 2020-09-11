package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayBank;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryBankAdapter;
import org.almostrealism.util.Producer;
import org.almostrealism.util.RankedChoiceProducer;

public class MeshData extends TriangleDataBank {
	/**
	 * If there is not enough RAM to run the entire kernel at once,
	 * it can be run one {@link Ray} at a time by enabling this flag.
	 */
	public static boolean enablePartialKernel = true;

	public MeshData(int triangles) {
		super(triangles);
	}

	public Scalar evaluateIntersection(Producer<Ray> ray, Object args[]) {
		ScalarBank distances = new ScalarBank(getCount(), CacheLevel.ACCESSED);
		RayBank in = new RayBank(1);
		PairBank out = new PairBank(1);

		PairBank conf = new PairBank(1);
		conf.set(0, new Pair(getCount(), Intersection.e));

		in.set(0, ray.evaluate(args));
		Triangle.intersectAt.kernelEvaluate(distances, new MemoryBank[] { in, this });
		RankedChoiceProducer.highestRank.kernelEvaluate(out, new MemoryBank[] { distances, conf });
		return new Scalar(out.get(0).getA());
	}

	public void evaluateIntersectionKernel(KernelizedProducer<Ray> ray, ScalarBank destination,
										   MemoryBank args[], int offset, int length) {
		PairBank result = new PairBank(destination.getCount());
		evaluateIntersectionKernel(ray, result, args, offset, length);
		for (int i = 0; i < result.getCount(); i++) {
			destination.get(i).setMem(new double[] { result.get(i).getA(), 1.0 });
		}
	}

	public void evaluateIntersectionKernel(KernelizedProducer<Ray> ray, PairBank destination,
										   MemoryBank args[], int offset, int length) {
		if (offset != 0 || length != destination.getCount()) {
			throw new IllegalArgumentException("Partial kernel evaluation is not supported");
		}

		long startTime = System.currentTimeMillis();
		RayBank rays = new RayBank(destination.getCount());
		ray.kernelEvaluate(rays, args, offset, length);
		System.out.println("MeshData: Evaluated ray kernel in " + (System.currentTimeMillis() - startTime) + " msec");

		PairBank dim = new PairBank(1);
		dim.set(0, new Pair(this.getCount(), rays.getCount()));

		if (enablePartialKernel) {
			ScalarBank distances = new ScalarBank(getCount(), CacheLevel.ACCESSED);
			RayBank in = new RayBank(1);
			PairBank out = new PairBank(1);

			PairBank conf = new PairBank(1);
			conf.set(0, new Pair(getCount(), Intersection.e));

			for (int i = 0; i < rays.getCount(); i++) {
				in.set(0, rays.get(i));
				Triangle.intersectAt.kernelEvaluate(distances, new MemoryBank[] { in, this, dim });
				RankedChoiceProducer.highestRank.kernelEvaluate(out, new MemoryBank[] { distances, conf });
				destination.set(i, out.get(0));
			}
		} else {
			ScalarBank distances = new ScalarBank(this.getCount() * rays.getCount(),
					MemoryBankAdapter.CacheLevel.NONE);

			startTime = System.currentTimeMillis();
			Triangle.intersectAt.kernelEvaluate(distances, new MemoryBank[] { rays, this, dim });
			System.out.println("MeshData: Completed intersection kernel in " +
					(System.currentTimeMillis() - startTime) + " msec");
			// TODO Choose best with highestRank kernel
		}
	}
}
