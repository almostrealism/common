package org.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.junit.Test;

public class KernelTest implements CodeFeatures {
	@Test
	public void copyRay() {
		TestKernel k = new TestKernel(Ray.blank(), ray(1, 2, 3, 4, 5, 6));
		Ray r = k.evaluate();
		System.out.println(r);
		assert r.equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 6)));
	}

	@Test
	public void copyRayKernel() {
		int count = 2;

		TestKernel k = new TestKernel(Ray.blank(),
									PassThroughEvaluable.of(Ray.class, 0));

		PackedCollection<Ray> output = Ray.bank(count, Ray.blank());
		PackedCollection<Ray> input = Ray.bank(count, ray(1, 2, 3,4, 5, 7));

		k.kernelEvaluate(output, new MemoryBank[] { input });

		for (int i = 0; i < count; i++) {
			System.out.println(output.get(i));
			assert output.get(i).equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 7)));
		}
	}
}
