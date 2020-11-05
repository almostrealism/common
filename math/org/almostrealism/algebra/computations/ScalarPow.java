package org.almostrealism.algebra.computations;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

public class ScalarPow extends DynamicAcceleratedProducerAdapter<Scalar> {
	private Expression<Double> value[];

	public ScalarPow(Producer<Scalar> base, Producer<Scalar> exponent) {
		super(2, Scalar.blank(), base, exponent);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return new Expression<>(Double.class, "pow(" + getArgumentValueName(1, 0) +
							", " + getArgumentValueName(2, 0) + ")",
							getArgument(1), getArgument(2));
				} else if (pos == 1) {
					// TODO  Certainty of exponent is ignored
					return new Expression<>(Double.class, "pow(" + getArgumentValueName(1, 1) +
							", " + getArgumentValueName(2, 0) + ")",
							getArgument(1), getArgument(2));
				} else {
					throw new IllegalArgumentException(String.valueOf(pos));
				}
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getArguments().get(0));

			for (int i = 1; i < getInputProducer(1).getArguments().size(); i++) {
				newArgs.add(getInputProducer(1).getArguments().get(i));
			}

			absorbVariables(getInputProducer(1));

			for (int i = 1; i < getInputProducer(2).getArguments().size(); i++) {
				newArgs.add(getInputProducer(2).getArguments().get(i));
			}

			absorbVariables(getInputProducer(2));

			// TODO  Certainty of exponent is ignored
			value = new Expression[] {
					new Expression<>(Double.class, "pow(" + getInputProducerValue(1, 0).getExpression() +
							", " + getInputProducerValue(2, 0).getExpression() + ")",
							getInputProducerValue(1, 0), getInputProducerValue(2, 0)),
					new Expression<>(Double.class, "pow(" + getInputProducerValue(1, 1).getExpression() +
							", " + getInputProducerValue(2, 0).getExpression() + ")",
							getInputProducerValue(1, 1), getInputProducerValue(2, 0))
			};

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			// setArguments(newArgs);
			removeDuplicateArguments();
		}

		convertToVariableRef();
	}
}
