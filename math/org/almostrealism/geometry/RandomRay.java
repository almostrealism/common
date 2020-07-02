package org.almostrealism.geometry;

import org.almostrealism.util.Producer;

public class RandomRay implements Producer<Ray> {
	/**
	 * Produce a ray with all values randomly selected
	 * between 0 and 1.
	 */
	@Override
	public Ray evaluate(Object[] args) {
		Ray r = new Ray();
		r.setMem(new double[] {
				Math.random(), Math.random(), Math.random(),
				Math.random(), Math.random(), Math.random() });
		return r;
	}

	/**
	 * Does nothing.
	 */
	@Override
	public void compact() {
	}
}
