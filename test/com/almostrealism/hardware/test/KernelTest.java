package com.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayBank;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.Provider;
import org.junit.Test;

public class KernelTest implements CodeFeatures {
	@Test
	public void copyRay() {
		TestKernel k = new TestKernel(() -> Ray.blank(), ray(1, 2, 3, 4, 5, 6));
		Ray r = k.evaluate();
		System.out.println(r);
		assert r.equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 6)));
	}

	@Test
	public void copyRayKernel() {
		int count = 2;

		TestKernel k = new TestKernel(() -> Ray.blank(),
									PassThroughProducer.of(Ray.class, 0));

		RayBank output = RayBank.fromProducer(() -> Ray.blank(), count);
		RayBank input = RayBank.fromProducer(
				ray(1, 2, 3,4, 5, 7), count);

		k.kernelEvaluate(output, new MemoryBank[] { input });

		for (int i = 0; i < count; i++) {
			System.out.println(output.get(i));
			assert output.get(i).equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 7)));
		}
	}
}
