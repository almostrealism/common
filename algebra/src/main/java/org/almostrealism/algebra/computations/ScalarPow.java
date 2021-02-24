package org.almostrealism.algebra.computations;

import io.almostrealism.code.expressions.Exponent;
import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarPow extends DynamicProducerComputationAdapter<Scalar, Scalar> implements ScalarProducer {
	private Expression<Double> value[];

	public ScalarPow(Supplier<Evaluable<? extends Scalar>> base, Supplier<Evaluable<? extends Scalar>> exponent) {
		super(2, Scalar.blank(), ScalarBank::new, base, exponent);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return new Exponent(getArgument(1).get(0), getArgument(2).get(0));
				} else if (pos == 1) {
					// TODO  Certainty of exponent is ignored
					return new Exponent(getArgument(1).get(1), getArgument(2).get(0));
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
			absorbVariables(getInputs().get(1));
			absorbVariables(getInputs().get(2));

			// TODO  Certainty of exponent is ignored
			value = new Expression[] {
					new Expression<>(Double.class, "pow(" + getInputValue(1, 0).getExpression() +
							", " + getInputValue(2, 0).getExpression() + ")",
							getInputValue(1, 0), getInputValue(2, 0)),
					new Expression<>(Double.class, "pow(" + getInputValue(1, 1).getExpression() +
							", " + getInputValue(2, 0).getExpression() + ")",
							getInputValue(1, 1), getInputValue(2, 0))
			};

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}
		}

		convertToVariableRef();
	}
}
