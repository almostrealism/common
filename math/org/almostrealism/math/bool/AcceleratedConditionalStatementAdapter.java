package org.almostrealism.math.bool;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicAcceleratedProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

public abstract class AcceleratedConditionalStatementAdapter<T extends MemWrapper>
											extends DynamicAcceleratedProducer<T>
											implements AcceleratedConditionalStatement<T> {
	private BiFunction<Function<Integer, String>, List<Variable>, String> compacted;

	public AcceleratedConditionalStatementAdapter(Producer<? extends MemWrapper> blankValue) {
		super(blankValue);
	}

	public AcceleratedConditionalStatementAdapter(Producer<? extends MemWrapper> blankValue,
												  Producer<Scalar> leftOperand,
												  Producer<Scalar> rightOperand,
												  Producer<T> trueValue,
												  Producer<T> falseValue) {
		super(blankValue, leftOperand, rightOperand, trueValue, falseValue);
	}

	public abstract int getMemLength();

	@Override
	public String getBody(Function<Integer, String> outputVariable, List<Variable> existingVariables) {
		if (compacted == null) {
			StringBuffer buf = new StringBuffer();

			writeVariables(buf::append, existingVariables);

			buf.append("if (");
			buf.append(getCondition());
			buf.append(") {\n");

			for (int i = 0; i < getMemLength(); i++) {
				buf.append("\t");
				buf.append(outputVariable.apply(i));
				buf.append(" = ");
				buf.append(getArgumentValueName(getTrueValue(), i));
				buf.append(";\n");
			}

			buf.append("} else {\n");

			for (int i = 0; i < getMemLength(); i++) {
				buf.append("\t");
				buf.append(outputVariable.apply(i));
				buf.append(" = ");
				buf.append(getArgumentValueName(getFalseValue(), i));
				buf.append(";\n");
			}

			buf.append("}\n");

			return buf.toString();
		} else {
			return compacted.apply(outputVariable, existingVariables);
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (!isCompactable()) return;

		compacted = (outputVariable, existingVariables) -> {
			StringBuffer buf = new StringBuffer();

			writeVariables(buf::append, existingVariables);

			List<Variable> allVariables = new ArrayList<>();
			allVariables.addAll(existingVariables);
			allVariables.addAll(getVariables());

			buf.append("if (");
			buf.append(getCondition());
			buf.append(") {\n");
			if (getTrueValue() != null) {
				buf.append(((DynamicAcceleratedProducer) getTrueValue().getProducer()).getBody(outputVariable, allVariables));
			}
			buf.append("} else {\n");
			if (getFalseValue() != null) {
				buf.append(((DynamicAcceleratedProducer) getFalseValue().getProducer()).getBody(outputVariable, allVariables));
			}
			buf.append("}\n");

			return buf.toString();
		};

		List<Argument> newArgs = new ArrayList<>();
		if (inputProducers[0] != null) newArgs.add(inputProducers[0]);
		getOperands().stream()
				.map(o -> excludeResult(((DynamicAcceleratedProducer) o.getProducer()).getInputProducers()))
				.flatMap(Stream::of)
				.forEach(newArgs::add);
		getOperands().stream()
				.map(Argument::getProducer)
				.forEach(this::absorbVariables);
		if (getTrueValue() != null) {
			newArgs.addAll(Arrays.asList(excludeResult(((DynamicAcceleratedProducer)
					getTrueValue().getProducer()).getInputProducers())));
		}

		if (getFalseValue() != null) {
			newArgs.addAll(Arrays.asList(excludeResult(((DynamicAcceleratedProducer)
					getFalseValue().getProducer()).getInputProducers())));
		}

		inputProducers = newArgs.toArray(new Argument[0]);
		removeDuplicateArguments();
	}

	protected boolean isCompactable() {
		if (compacted != null) return false;

		if (getTrueValue() != null && getTrueValue().getProducer() instanceof DynamicAcceleratedProducer == false) return false;
		if (getFalseValue() != null && getFalseValue().getProducer() instanceof DynamicAcceleratedProducer == false) return false;
		for (Argument a : getOperands()) {
			if (a.getProducer() instanceof DynamicAcceleratedProducerAdapter == false) return false;
		}

		return true;
	}

	protected boolean isCompacted() { return compacted != null; }
}
