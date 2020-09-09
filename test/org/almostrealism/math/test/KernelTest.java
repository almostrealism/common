package org.almostrealism.math.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayBank;
import org.almostrealism.math.MemoryBank;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class KernelTest {
	@Test
	public void copyRay() {
		TestKernel k = new TestKernel(Ray.blank(),
				StaticProducer.of(
						new Ray(new Vector(1, 2, 3),
								new Vector(4, 5, 6))));
		Ray r = k.evaluate();
		System.out.println(r);
		assert r.equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 6)));
	}

	@Test
	public void copyRayKernel() {
		int count = 2;

		TestKernel k = new TestKernel(Ray.blank(),
									new PassThroughProducer<>(0));

		RayBank output = RayBank.fromProducer(Ray.blank(), count);
		RayBank input = RayBank.fromProducer(StaticProducer.of(
				new Ray(new Vector(1, 2, 3),
						new Vector(4, 5, 7))), count);

		k.kernelEvaluate(output, new MemoryBank[] { input });

		for (int i = 0; i < count; i++) {
			System.out.println(output.get(i));
			assert output.get(i).equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 7)));
		}
	}
}
