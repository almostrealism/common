package org.almostrealism.algebra.computations;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

public class ScalarPow extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	private Expression<Double> value[];

	public ScalarPow(Producer<Scalar> base, Producer<Scalar> exponent) {
		super(2, Scalar.blank(), base, exponent);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				String v1 = getFunctionName() + "_v1";
				String v2 = getFunctionName() + "_v2";

				if (pos == 0) {
					return new Expression<>("pow(" + getArgumentValueName(1, 0) +
							", " + getArgumentValueName(2, 0) + ")");
				} else if (pos == 1) {
					// TODO  Certainty of exponent is ignored
					return new Expression<>("pow(" + getArgumentValueName(1, 1) +
							", " + getArgumentValueName(2, 0) + ")");
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
			newArgs.add(inputProducers[0]);

			for (int i = 1; i < getInputProducer(1).getInputProducers().length; i++) {
				newArgs.add(getInputProducer(1).getInputProducers()[i]);
			}

			absorbVariables(getInputProducer(1));

			for (int i = 1; i < getInputProducer(2).getInputProducers().length; i++) {
				newArgs.add(getInputProducer(2).getInputProducers()[i]);
			}

			absorbVariables(getInputProducer(2));

			// TODO  Certainty of exponent is ignored
			value = new Expression[] {
					new Expression<>("pow(" + getInputProducerValue(1, 0).getExpression() +
							", " + getInputProducerValue(2, 0).getExpression() + ")"),
					new Expression<>("pow(" + getInputProducerValue(1, 1).getExpression() +
							", " + getInputProducerValue(2, 0).getExpression() + ")")
			};

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}

		convertToVariableRef();
	}

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) { return new ScalarBank(size); }
}
