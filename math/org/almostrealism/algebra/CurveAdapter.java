package org.almostrealism.algebra;

public abstract class CurveAdapter implements Curve {
	/** Delegates to {@link #getNormalAt(Vector)}. */
	public Vector operate(Vector v ) { return getNormalAt(v); }
}
