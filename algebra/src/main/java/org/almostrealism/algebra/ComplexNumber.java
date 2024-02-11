package org.almostrealism.algebra;

import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;

public class ComplexNumber extends Pair {
	public ComplexNumber() {
	}

	public ComplexNumber(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	public ComplexNumber(double x, double y) {
		super(x, y);
	}

	public double getRealPart() { return left(); }
	public double getImaginaryPart() { return right(); }
	public double r() { return getRealPart(); }
	public double i() { return getImaginaryPart(); }

	public static BiFunction<MemoryData, Integer, Pair<?>> postprocessor() {
		return (delegate, offset) -> new ComplexNumber(delegate, offset);
	}
}
