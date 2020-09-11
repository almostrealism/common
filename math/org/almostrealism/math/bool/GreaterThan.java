package org.almostrealism.math.bool;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;

import java.util.function.Function;

public class GreaterThan<T extends MemWrapper> extends AcceleratedBinaryConditionAdapter<T> {
	public GreaterThan(int memLength) {
		this(memLength, DynamicProducer.forMemLength());
	}

	public GreaterThan(int memLength,
					   Function<Integer, Producer<? extends MemWrapper>> blankValue) {
		this(memLength, blankValue, null, null, null, null);
	}

	public GreaterThan(int memLength,
					   Function<Integer, Producer<? extends MemWrapper>> blankValue,
					   Producer<Scalar> leftOperand,
					   Producer<Scalar> rightOperand,
					   Producer<T> trueValue,
					   Producer<T> falseValue) {
		this(memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public GreaterThan(int memLength,
					   Producer<Scalar> leftOperand,
					   Producer<Scalar> rightOperand) {
		this(memLength, (Producer) null, leftOperand, rightOperand, null, null, false);
	}

	public GreaterThan(int memLength,
					   Producer<? extends MemWrapper> blankValue,
					   Producer<Scalar> leftOperand,
					   Producer<Scalar> rightOperand,
					   Producer<T> trueValue,
					   Producer<T> falseValue) {
		this(memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue, false);
	}
	public GreaterThan(int memLength,
					   Producer<? extends MemWrapper> blankValue,
					   Producer<Scalar> leftOperand,
					   Producer<Scalar> rightOperand,
					   Producer<T> trueValue,
					   Producer<T> falseValue,
					   boolean includeEqual) {
		super(includeEqual ? ">=" : ">", memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
	}
}
