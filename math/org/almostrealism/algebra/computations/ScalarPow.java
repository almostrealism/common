package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class ScalarPow extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	private Producer<Scalar> base, exponent;

	private String value[];

	public ScalarPow(Producer<Scalar> base, Producer<Scalar> exponent) {
		super(2, Scalar.blank(), base, exponent);
		this.base = base;
		this.exponent = exponent;
	}

	@Override
	public String getValue(int pos) {
		if (value == null) {
			String v1 = getFunctionName() + "_v1";
			String v2 = getFunctionName() + "_v2";

			if (pos == 0) {
				return "pow(" + v1 + "[" + v1 + "Offset], " + v2 + "[" + v2 + "Offset])";
			} else if (pos == 1) {
				// TODO  Certainty of exponent is ignored
				return "pow(" + v1 + "[" + v1 + "Offset + 1], " + v2 + "[" + v2 + "Offset])";
			} else {
				throw new IllegalArgumentException(String.valueOf(pos));
			}
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		this.base.compact();
		this.exponent.compact();

		if (value == null && isCompletelyDynamicAcceleratedAdapters()) {
			DynamicAcceleratedProducerAdapter bd = (DynamicAcceleratedProducerAdapter) base;
			DynamicAcceleratedProducerAdapter ed = (DynamicAcceleratedProducerAdapter) exponent;

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);

			for (int i = 1; i < bd.getInputProducers().length; i++) {
				newArgs.add(bd.getInputProducers()[i]);
			}

			for (int i = 1; i < ed.getInputProducers().length; i++) {
				newArgs.add(ed.getInputProducers()[i]);
			}

			// TODO  Certainty of exponent is ignored
			value = new String[] {
					"pow(" + bd.getValue(0) + ", " + ed.getValue(0) + ")",
					"pow(" + bd.getValue(1) + ", " + ed.getValue(0) + ")"
			};

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
