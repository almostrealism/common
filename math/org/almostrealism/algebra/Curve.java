package org.almostrealism.algebra;

import java.util.concurrent.Callable;

import org.almostrealism.space.Gradient;

public interface Curve<T> extends Gradient, Callable<T> {
}
