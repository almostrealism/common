package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.TranslationMatrix;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

public class TransformTest implements HardwareFeatures, CodeFeatures {
	protected TransformMatrix matrix() {
		return new TranslationMatrix(vector(0.0, 10.0, 0.0)).evaluate();
	}

	protected Evaluable<Vector> transformAsLocation() {
		return transformAsLocation(matrix(), vector(1.0, 2.0, 3.0)).get();
	}

	@Test
	public void applyAsLocation() {
		Vector v = transformAsLocation().evaluate();
		locationAssertions(v);
	}

	protected void locationAssertions(Vector v) {
		Assert.assertEquals(v.getX(), 1.0, Math.pow(10, -10));
		Assert.assertEquals(v.getY(), 12.0, Math.pow(10, -10));
		Assert.assertEquals(v.getZ(), 3.0, Math.pow(10, -10));
	}
}
