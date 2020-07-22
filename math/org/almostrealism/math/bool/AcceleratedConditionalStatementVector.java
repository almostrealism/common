package org.almostrealism.math.bool;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.CollectionUtils;
import org.almostrealism.util.Producer;

public interface AcceleratedConditionalStatementVector extends AcceleratedConditionalStatement<Vector>, VectorProducer {
	default AcceleratedConjunctionVector and(AcceleratedConditionalStatement<Vector> operand, Producer<Vector> trueValue, Producer<Vector> falseValue) {
		return and(trueValue, falseValue, operand);
	}

	default AcceleratedConjunctionVector and(Producer<Vector> trueValue, Producer<Vector> falseValue, AcceleratedConditionalStatement<Vector>... operands) {
		return new AcceleratedConjunctionVector(trueValue, falseValue,
				CollectionUtils.include(new AcceleratedConditionalStatement[0], this, operands));
	}
}
