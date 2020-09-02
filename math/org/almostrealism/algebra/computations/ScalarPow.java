package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class ScalarPow extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	private String value[];

	public ScalarPow(Producer<Scalar> base, Producer<Scalar> exponent) {
		super(2, Scalar.blank(), base, exponent);
	}

	@Override
	public String getValue(Argument arg, int pos) {
		if (value == null) {
			String v1 = getFunctionName() + "_v1";
			String v2 = getFunctionName() + "_v2";

			if (pos == 0) {
				return "pow(" + getArgumentValueName(1, 0) + ", " + getArgumentValueName(2, 0) + ")";
			} else if (pos == 1) {
				// TODO  Certainty of exponent is ignored
				return "pow(" + getArgumentValueName(1, 1) + ", " + getArgumentValueName(2, 0) + ")";
			} else {
				throw new IllegalArgumentException(String.valueOf(pos));
			}
		} else {
			return value[pos];
		}
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

			for (int i = 1; i < getInputProducer(2).getInputProducers().length; i++) {
				newArgs.add(getInputProducer(2).getInputProducers()[i]);
			}

			// TODO  Certainty of exponent is ignored
			value = new String[] {
					"pow(" + getInputProducerValue(1, 0) + ", " + getInputProducerValue(2, 0) + ")",
					"pow(" + getInputProducerValue(1, 1) + ", " + getInputProducerValue(2, 0) + ")"
			};

			for (int i = 0; i < value.length; i++) {
				if (value[i].contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}