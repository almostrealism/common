package org.almostrealism.math.bool;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;

import java.util.function.Function;

public class LessThan<T extends MemWrapper> extends AcceleratedBinaryConditionAdapter<T> {
	public LessThan(int memLength) {
		this(memLength, DynamicProducer.forMemLength());
	}

	public LessThan(int memLength,
					   Function<Integer, Producer<? extends MemWrapper>> blankValue) {
		this(memLength, blankValue, null, null, null, null);
	}

	public LessThan(int memLength,
					   Function<Integer, Producer<? extends MemWrapper>> blankValue,
					   Producer<Scalar> leftOperand,
					   Producer<Scalar> rightOperand,
					   Producer<T> trueValue,
					   Producer<T> falseValue) {
		this(memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public LessThan(int memLength,
					Producer<? extends MemWrapper> blankValue,
					Producer<Scalar> leftOperand,
					Producer<Scalar> rightOperand,
					Producer<T> trueValue,
					Producer<T> falseValue,
					boolean includeEqual) {
		super(includeEqual ? "<=" : "<", memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
	}
}
