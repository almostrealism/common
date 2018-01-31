package org.almostrealism.econ;

public interface Currency<T extends Currency> {
	T multiply(double amount);
}
