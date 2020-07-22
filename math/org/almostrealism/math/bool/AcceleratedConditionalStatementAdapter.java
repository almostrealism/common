package org.almostrealism.math.bool;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.math.DynamicAcceleratedProducer;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public abstract class AcceleratedConditionalStatementAdapter<T extends MemWrapper>
											extends DynamicAcceleratedProducer<T>
											implements AcceleratedConditionalStatement<T> {
	private Function<Function<Integer, String>, String> compacted;

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
	public String getBody(Function<Integer, String> outputVariable) {
		if (compacted == null) {
			StringBuffer buf = new StringBuffer();

			getVariables().forEach(var -> {
				buf.append("double ");
				buf.append(var.getName());
				buf.append(" = ");
				buf.append(var.getData());
				buf.append(";\n");
			});

			buf.append("if (");
			buf.append(getCondition());
			buf.append(") {\n");

			for (int i = 0; i < getMemLength(); i++) {
				buf.append("\t");
				buf.append(outputVariable.apply(i));
				buf.append(" = ");
				buf.append(getTrueValue().getName());
				buf.append("[");
				buf.append(getTrueValue().getName());
				buf.append("Offset");
				buf.append("+");
				buf.append(i);
				buf.append("];\n");
			}

			buf.append("} else {\n");

			for (int i = 0; i < getMemLength(); i++) {
				buf.append("\t");
				buf.append(outputVariable.apply(i));
				buf.append(" = ");
				buf.append(getFalseValue().getName());
				buf.append("[");
				buf.append(getFalseValue().getName());
				buf.append("Offset");
				buf.append("+");
				buf.append(i);
				buf.append("];\n");
			}

			buf.append("}\n");

			return buf.toString();
		} else {
			return compacted.apply(outputVariable);
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (!isCompactable()) return;

		compacted = outputVariable -> {
			StringBuffer buf = new StringBuffer();

			getVariables().forEach(var -> {
				buf.append("double ");
				buf.append(var.getName());
				buf.append(" = ");
				buf.append(var.getData());
				buf.append(";\n");
			});

			buf.append("if (");
			buf.append(getCondition());
			buf.append(") {\n");
			if (getTrueValue() != null) {
				buf.append(((DynamicAcceleratedProducer) getTrueValue().getProducer()).getBody(outputVariable));
			}
			buf.append("} else {\n");
			if (getFalseValue() != null) {
				buf.append(((DynamicAcceleratedProducer) getFalseValue().getProducer()).getBody(outputVariable));
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
