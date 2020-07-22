package org.almostrealism.math.bool;

import io.almostrealism.code.Variable;
import org.almostrealism.math.AcceleratedProducer;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.Producer;

import java.util.Arrays;
import java.util.List;

public interface AcceleratedConditionalStatement<T extends MemWrapper> extends Producer<T> {
	String getCondition();

	// TODO  Maybe this should be a Computation, or maybe this should go in another interface
	// TODO  Maybe the type argument should be MemWrapper? Or Scalar? (instead of String)
	default List<Variable<String>> getVariables() { return Arrays.asList(); }

	List<AcceleratedProducer.Argument> getOperands();

	AcceleratedProducer.Argument getTrueValue();
	AcceleratedProducer.Argument getFalseValue();
}
