package org.almostrealism.util;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.DynamicAcceleratedProducer;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.MemoryBank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AcceleratedRankedChoiceProducer<T extends MemWrapper> extends DynamicAcceleratedProducer<T> implements DimensionAware {
	public static final boolean enableCompaction = true;
	public static final boolean enableOpenClKernelWorkaround = true;

	private int memLength;
	private int valueCount;
	private double e;
	private Supplier<T> onNull;
	private IntFunction<MemoryBank<T>> forKernel;

	private Function<List<Variable>, String> compactedRanks[];
	private Function<List<Variable>, String> compactedChoices[];
	private Function<List<Variable>, String> compactedDefaultValue;

	private List<Argument> ranks, choices;
	private Argument defaultValue;

	public AcceleratedRankedChoiceProducer(int memLength, Producer<T> blank, IntFunction<MemoryBank<T>> forKernel,
										   List<ProducerWithRank<T>> values, Producer<T> defaultValue, double e) {
		this(memLength, blank, forKernel, values, defaultValue, e, null);
	}

	public AcceleratedRankedChoiceProducer(int memLength, Producer<T> blank, IntFunction<MemoryBank<T>> forKernel,
										   List<ProducerWithRank<T>> values, Producer<T> defaultValue,
										   double e, Supplier<T> onNull) {
		super(generateArgs(blank, values, defaultValue));
		this.memLength = memLength;
		this.forKernel = forKernel;
		this.valueCount = values.size();
		this.e = e;
		this.onNull = onNull;
		this.ranks = getRanks();
		this.choices = getChoices();
		this.defaultValue = getDefaultValue();
		addVariable(new Variable(getHighestRankResultVariable(), this, 2, "__local"));
		addVariable(new Variable(getHighestRankInputVariable(), this, 2 * valueCount, "__local"));
		addVariable(new Variable(getHighestRankConfVariable(), this, 2, "__local"));
	}

//	public T evaluate(Object args[]) {
//		for (int i = 1; i < inputProducers.length; i++) {
//			System.out.println("input[" + i + "] = " + inputProducers[i].getProducer().evaluate(args));
//		}
//
//		return super.evaluate(args);
//	}


	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		getRanks().stream().map(Argument::getProducer).filter(p -> p instanceof DimensionAware)
				.forEach(p -> ((DimensionAware) p).setDimensions(width, height, ssw, ssh));
		getChoices().stream().map(Argument::getProducer).filter(p -> p instanceof DimensionAware)
				.forEach(p -> ((DimensionAware) p).setDimensions(width, height, ssw, ssh));
		if (getDefaultValue().getProducer() instanceof DimensionAware) {
			((DimensionAware) getDefaultValue().getProducer()).setDimensions(width, height, ssw, ssh);
		}
	}

	@Override
	public String getBody(Function<Integer, String> outputVariable, List<Variable> existingVariables) {
		StringBuffer buf = new StringBuffer();

		List<Variable> variables = new ArrayList<>();
		variables.addAll(existingVariables);

		writeVariables(buf::append, variables);
		variables.addAll(getVariables());

		writeInputAssignments(buf::append, variables);
		buf.append("highestRankLocal(");
		buf.append(getHighestRankResultVariable());
		buf.append(", ");
		buf.append(getHighestRankInputVariable());
		buf.append(", ");
		buf.append(getHighestRankConfVariable());
		buf.append(", 0, 0, 0, 2, 2, 2);\n");
//		writeHighestRank(buf::append);
//		buf.append("printf(\"rank = %f, choice = %f\\n\", " +
//				getHighestRankResultVariable() + "[0]," +
//				getHighestRankResultVariable() + "[1]);\n");
		if (enableOpenClKernelWorkaround) buf.append("printf(\"\");\n");
		writeOutputAssignments(buf::append, variables);

		return buf.toString();
	}

	protected void writeInputAssignments(Consumer<String> output, List<Variable> existingVariables) {
		List<Argument> ranks = getRanks();

		IntStream.range(0, ranks.size()).forEach(i -> {
			if (compactedRanks == null || compactedRanks[i] == null) {
				output.accept(getHighestRankInputVariable());
				output.accept("[");
				output.accept(String.valueOf(2 * i));
				output.accept("] = ");
				output.accept(getArgumentValueName(ranks.get(i), 0));
				output.accept(";\n");
			} else {
				output.accept(compactedRanks[i].apply(existingVariables));
			}
		});

		output.accept(getHighestRankConfVariable());
		output.accept("[0] = ");
		output.accept(stringForDouble(ranks.size()));
		output.accept(";\n");

		output.accept(getHighestRankConfVariable());
		output.accept("[1] = ");
		output.accept(stringForDouble(e));
		output.accept(";\n");
	}

	protected void writeOutputAssignments(Consumer<String> output, List<Variable> existingVariables) {
		List<Argument> choices = getChoices();
		IntStream.range(0, choices.size()).forEach(i -> {
			output.accept("if (");
			output.accept(stringForDouble(i));
			output.accept(" == ");
			output.accept(getHighestRankResultVariable());
			output.accept("[1]) {\n");
			writeOutputAssignments(output, i, existingVariables);
			output.accept("}");
			output.accept(" else ");
		});

		output.accept("{\n");
		writeOutputAssignments(output, -1, existingVariables);
		output.accept("}\n");
	}

	protected void writeOutputAssignments(Consumer<String> output, int index, List<Variable> existingVariables) {
		if (index < 0) {
			if (compactedDefaultValue == null) {
				IntStream.range(0, memLength).forEach(i -> {
					output.accept(getArgumentValueName(0, i, true));
					output.accept(" = ");
					output.accept(getArgumentValueName(getDefaultValue(), i, false));
					output.accept(";\n");
				});
			} else {
				output.accept(compactedDefaultValue.apply(existingVariables));
			}
		} else if (compactedChoices == null || compactedChoices[index] == null) {
			IntStream.range(0, memLength).forEach(i -> {
				output.accept(getArgumentValueName(0, i, true));
				output.accept(" = ");
				output.accept(getArgumentValueName(indexOfChoice().applyAsInt(index), i, false));
				output.accept(";\n");
			});
		} else {
			output.accept(compactedChoices[index].apply(existingVariables));
		}
	}

	protected void writeHighestRank(Consumer<String> output) {
		output.accept("__local double closest;\n");
		output.accept("closest = -1.0;\n");
		output.accept("__local int closestIndex;\n");
		output.accept("closestIndex = -1;\n");
		output.accept("for (int i = 0; i < ");
		output.accept(getHighestRankConfVariable());
		output.accept("[0]; i++) {\n");
		output.accept("__local double value;\n");
		output.accept("value = ");
		output.accept(getHighestRankInputVariable());
		output.accept("[i * 2];\n");
		output.accept("if (value >= ");
		output.accept(getHighestRankConfVariable());
		output.accept("[1]");
		output.accept(") {\n");
		output.accept("if (closestIndex == -1 || value < closest) {\n");
		output.accept("closest = value;\n");
		output.accept("closestIndex = i;\n");
		output.accept("}\n");
		output.accept("}\n");
		output.accept("}\n");
		output.accept(getHighestRankResultVariable());
		output.accept("[0] = closestIndex < 0 ? -1 : closest;\n");
		output.accept(getHighestRankResultVariable());
		output.accept("[1] = closestIndex;\n");
	}

	protected String getHighestRankResultVariable() { return getVariableName(0); }
	protected String getHighestRankInputVariable() { return getVariableName(1); }
	protected String getHighestRankConfVariable() { return getVariableName(2); }
	public int getDefaultValueIndex() { return getInputProducers().length - 1; }

	public List<Argument> getRanks() { return ranks == null ? getArguments(i -> i + 1) : ranks; }
	public List<Argument> getChoices() { return choices == null ? getArguments(indexOfChoice()) : choices; }
	public Argument getDefaultValue() { return defaultValue == null ? getInputProducers()[getInputProducers().length - 1] : defaultValue; }

	private List<Argument> getArguments(IntUnaryOperator index) {
		return IntStream.range(0, valueCount)
				.map(index)
				.mapToObj(i -> inputProducers[i])
				.collect(Collectors.toList());
	}

	private IntUnaryOperator indexOfChoice() { return i -> i + valueCount + 1; }

	public void compact() {
		super.compact();

		if (enableCompaction && (compactedRanks == null || compactedChoices == null)) {
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getInputProducers()[0]);

			List<Argument> ranks = getRanks();
			compactedRanks = new Function[ranks.size()];
			IntStream.range(0, compactedRanks.length).forEach(i -> {
				if (ranks.get(i).getProducer() instanceof DynamicAcceleratedProducer) {
					DynamicAcceleratedProducer p = (DynamicAcceleratedProducer) ranks.get(i).getProducer();
					compactedRanks[i] = ev -> {
						String s = p.getBody((Function<Integer, String>) pos ->
								getHighestRankInputVariable() + "[" + (2 * i + pos) + "]", ev);
						ev.addAll(p.getVariables());
						return s;
					};

					newArgs.addAll(Arrays.asList(excludeResult(p.getInputProducers())));
				} else {
					newArgs.add(ranks.get(i));
				}
			});

			List<Argument> choices = getChoices();
			compactedChoices = new Function[choices.size()];
			IntStream.range(0, compactedChoices.length).forEach(i -> {
				if (choices.get(i).getProducer() instanceof DynamicAcceleratedProducer) {
					DynamicAcceleratedProducer p = (DynamicAcceleratedProducer) choices.get(i).getProducer();
					compactedChoices[i] = ev -> {
						String s = p.getBody((Function<Integer, String>) pos ->
								getArgumentValueName(0, pos, true), ev);
						ev.addAll(p.getVariables());
						return s;
					};

					newArgs.addAll(Arrays.asList(excludeResult(p.getInputProducers())));
				} else {
					newArgs.add(choices.get(i));
				}
			});

			Argument defaultValue = getDefaultValue();
			if (defaultValue.getProducer() instanceof DynamicAcceleratedProducer) {
				DynamicAcceleratedProducer p = (DynamicAcceleratedProducer) defaultValue.getProducer();
				compactedDefaultValue = ev -> {
					String s = p.getBody((Function<Integer, String>) pos ->
							getArgumentValueName(0, pos, true), ev);
					ev.addAll(p.getVariables());
					return s;
				};

				newArgs.addAll(Arrays.asList(excludeResult(p.getInputProducers())));
			} else {
				newArgs.add(defaultValue);
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}

	@Override
	protected T handleNull(int argIndex) {
		return onNull == null ? super.handleNull(argIndex) : onNull.get();
	}

	@Override
	public MemoryBank<T> createKernelDestination(int size) { return forKernel.apply(size); }

	private static <T> Producer[] generateArgs(Producer<T> blank, List<ProducerWithRank<T>> values, Producer<T> defaultValue) {
		List<Producer> args = new ArrayList<>();
		args.add(blank);
		values.stream().map(ProducerWithRank::getRank).forEach(args::add);
		values.stream().map(ProducerWithRank::getProducer).forEach(args::add);
		args.add(defaultValue);
		return args.toArray(new Producer[0]);
	}
}
