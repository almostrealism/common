package org.almostrealism.heredity;

public interface ScaleFactor<T> extends Factor<T> {
	void setScale(double s);

	double getScale();
}
