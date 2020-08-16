package org.almostrealism.algebra.test;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.TransformAsLocation;
import org.almostrealism.geometry.TranslationMatrix;
import org.almostrealism.util.StaticProducer;
import org.junit.Assert;
import org.junit.Test;

public class TransformTest {
	protected TransformMatrix matrix() {
		return new TranslationMatrix(StaticProducer.of(
				new Vector(0.0, 10.0, 0.0))).evaluate();
	}

	protected TransformAsLocation transformAsLocation() {
		return new TransformAsLocation(matrix(), StaticProducer.of(new Vector(1.0, 2.0, 3.0)));
	}

	@Test
	public void applyAsLocation() {
		Vector v = transformAsLocation().evaluate();
		locationAssertions(v);
	}

	@Test
	public void applyAsLocationCompact() {
		TransformAsLocation t = transformAsLocation();
		t.compact();

		System.out.println(t.getFunctionDefinition());

		Vector v = t.evaluate();
		locationAssertions(v);
	}

	protected void locationAssertions(Vector v) {
		Assert.assertEquals(v.getX(), 1.0, Math.pow(10, -10));
		Assert.assertEquals(v.getY(), 12.0, Math.pow(10, -10));
		Assert.assertEquals(v.getZ(), 3.0, Math.pow(10, -10));
	}
}
