package org.almostrealism.algebra.test;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.computations.RayMatrixTransform;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.TranslationMatrix;
import org.almostrealism.util.StaticProducer;
import org.junit.Assert;
import org.junit.Test;

public class RayMatrixTransformTest {
	protected RayMatrixTransform transform() {
		TransformMatrix m = new TranslationMatrix(StaticProducer.of(
				new Vector(0.0, -10.0, 0.0))).evaluate();

		Ray r = new Ray(new Vector(1.0, 2.0, 3.0), new Vector(4.0, 5.0, 6.0));
		return new RayMatrixTransform(m.getInverse(), StaticProducer.of(r));
	}

	@Test
	public void apply() {
		Ray r = transform().evaluate();
		assertions(r);
	}

	@Test
	public void applyCompact() {
		RayMatrixTransform t = transform();
		t.compact();

		System.out.println(t.getFunctionDefinition());

		Ray r = t.evaluate();
		assertions(r);
	}

	protected void assertions(Ray r) {
		Assert.assertEquals(1.0, r.getOrigin().getX(), Math.pow(10, -10));
		Assert.assertEquals(12.0, r.getOrigin().getY(), Math.pow(10, -10));
		Assert.assertEquals(3.0, r.getOrigin().getZ(), Math.pow(10, -10));
		Assert.assertEquals(4.0, r.getDirection().getX(), Math.pow(10, -10));
		Assert.assertEquals(5.0, r.getDirection().getY(), Math.pow(10, -10));
		Assert.assertEquals(6.0, r.getDirection().getZ(), Math.pow(10, -10));
	}
}