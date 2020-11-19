package org.almostrealism.math.bool;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;

import java.util.function.Function;
import java.util.function.Supplier;

public class GreaterThan<T extends MemWrapper> extends AcceleratedBinaryConditionAdapter<T> {
	public GreaterThan(int memLength,
					   Function<Integer, Supplier<Producer<T>>> blankValue) {
		this(memLength, blankValue, null, null, null, null);
	}

	public GreaterThan(int memLength,
					   Function<Integer, Supplier<Producer<T>>> blankValue,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand,
					   Supplier<Producer<T>> trueValue,
					   Supplier<Producer<T>> falseValue) {
		this(memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public GreaterThan(int memLength,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand) {
		this(memLength, null, leftOperand, rightOperand, null, null, false);
	}

	public GreaterThan(int memLength,
					   Supplier<Producer<T>> blankValue,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand,
					   Supplier<Producer<T>> trueValue,
					   Supplier<Producer<T>> falseValue) {
		this(memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue, false);
	}
	public GreaterThan(int memLength,
					   Supplier<Producer<T>> blankValue,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand,
					   Supplier<Producer<T>> trueValue,
					   Supplier<Producer<T>> falseValue,
					   boolean includeEqual) {
		super(includeEqual ? ">=" : ">", memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
	}
}
