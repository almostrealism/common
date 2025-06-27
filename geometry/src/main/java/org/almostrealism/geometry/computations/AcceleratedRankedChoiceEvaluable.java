/*
 * Copyright 2022 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.geometry.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.scope.Variable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.geometry.DimensionAware;
import org.almostrealism.hardware.DynamicAcceleratedEvaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ProducerWithRank;
import io.almostrealism.code.Precision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AcceleratedRankedChoiceEvaluable<T extends MemoryData>
		extends DynamicAcceleratedEvaluable<T, T> implements DimensionAware, ExpressionFeatures {
	public static final boolean enableCompaction = true;

	private int memLength;
	private int valueCount;
	private double e;
	private Supplier<T> onNull;
	private IntFunction<MemoryBank<T>> forKernel;

	private Function<List<Variable<?, ?>>, String> compactedRanks[];
	private Function<List<Variable<?, ?>>, String> compactedChoices[];
	private Function<List<Variable<?, ?>>, String> compactedDefaultValue;

	private List<ArrayVariable<Scalar>> ranks;
	private List<ArrayVariable<T>> choices;
	private ArrayVariable defaultValue;

	private LanguageOperations lang;

	public AcceleratedRankedChoiceEvaluable(int memLength, Supplier<T> blank, IntFunction<MemoryBank<T>> forKernel,
											List<ProducerWithRank<T, Scalar>> values, Supplier<Evaluable<? extends T>> defaultValue, double e) {
		this(memLength, blank, forKernel, values, defaultValue, e, null);
	}

	public AcceleratedRankedChoiceEvaluable(int memLength, Supplier<T> blank, IntFunction<MemoryBank<T>> forKernel,
											List<ProducerWithRank<T, Scalar>> values, Supplier<Evaluable<? extends T>> defaultValue,
											double e, Supplier<T> onNull) {
		super(null, blank, forKernel, generateArgs(values, defaultValue));
		this.memLength = memLength;
		this.forKernel = forKernel;
		this.valueCount = values.size();
		this.e = e;
		this.onNull = onNull;
	}

	public void addVariable(Variable<?, ?> v) {
		// TODO
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		this.lang = manager.getLanguage();
		this.ranks = getRanks();
		this.choices = getChoices();
		this.defaultValue = getDefaultValue();
		addVariable(new ArrayVariable(this, PhysicalScope.LOCAL, Double.class, getHighestRankResultVariable(), e(2), () -> this));
		addVariable(new ArrayVariable(this, PhysicalScope.LOCAL, Double.class, getHighestRankInputVariable(), e(2 * valueCount), () -> this));
		addVariable(new ArrayVariable(this, PhysicalScope.LOCAL, Double.class, getHighestRankConfVariable(), e(2), () -> this));
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		getRanks().stream().map(ArrayVariable::getProducer).filter(p -> p instanceof DimensionAware)
				.forEach(p -> ((DimensionAware) p).setDimensions(width, height, ssw, ssh));
		getChoices().stream().map(ArrayVariable::getProducer).map(Supplier::get).filter(p -> p instanceof DimensionAware)
				.forEach(p -> ((DimensionAware) p).setDimensions(width, height, ssw, ssh));
		if (getDefaultValue().getProducer() instanceof DimensionAware) {
			((DimensionAware) getDefaultValue().getProducer()).setDimensions(width, height, ssw, ssh);
		}
	}

	protected void writeInputAssignments(Consumer<String> output, List<Variable<?, ?>> existingVariables, LanguageOperations lang) {
		List<ArrayVariable<Scalar>> ranks = getRanks();

		IntStream.range(0, ranks.size()).forEach(i -> {
			if (compactedRanks == null || compactedRanks[i] == null) {
				output.accept(getHighestRankInputVariable());
				output.accept("[");
				output.accept(String.valueOf(2 * i));
				output.accept("] = ");
				output.accept(getVariableValueName(ranks.get(i), 0));
				output.accept(";\n");
			} else {
				output.accept(compactedRanks[i].apply(existingVariables));
			}
		});

		output.accept(getHighestRankConfVariable());
		output.accept("[0] = ");
		output.accept(lang.getPrecision().stringForDouble(ranks.size()));
		output.accept(";\n");

		output.accept(getHighestRankConfVariable());
		output.accept("[1] = ");
		output.accept(lang.getPrecision().stringForDouble(e));
		output.accept(";\n");
	}

	protected void writeOutputAssignments(Consumer<String> output, List<Variable<?, ?>> existingVariables) {
		List<ArrayVariable<T>> choices = getChoices();
		IntStream.range(0, choices.size()).forEach(i -> {
			output.accept("if (");
			output.accept(lang.getPrecision().stringForDouble(i));
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

	protected void writeOutputAssignments(Consumer<String> output, int index, List<Variable<?, ?>> existingVariables) {
		if (index < 0) {
			if (compactedDefaultValue == null) {
				IntStream.range(0, memLength).forEach(i -> {
					output.accept(getArgumentValueName(0, i, true));
					output.accept(" = ");
					output.accept(getVariableValueName(getDefaultValue(), i, false));
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

//				if (enableOpenClKernelWorkaround) {
//					output.accept("printf(\"value[" + i + "] = %f\\n\", ");
//					output.accept(getArgumentValueName(indexOfChoice().applyAsInt(index), i, false));
//					output.accept(");\n");
//				}
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
	public int getDefaultValueIndex() { return getArgumentVariables().size() - 1; }

	public List<ArrayVariable<Scalar>> getRanks() { return ranks == null ? getArguments(i -> i + 1) : ranks; }
	public List<ArrayVariable<T>> getChoices() { return choices == null ? getArguments(indexOfChoice()) : choices; }
	public ArrayVariable getDefaultValue() { return defaultValue == null ? getArgumentVariables().get(getArgumentVariables().size() - 1) : defaultValue; }

	private <T> List<ArrayVariable<T>> getArguments(IntUnaryOperator index) {
		return IntStream.range(0, valueCount)
				.map(index)
				.mapToObj(i -> (ArrayVariable<T>) getArgumentVariables().get(i))
				.collect(Collectors.toList());
	}

	private IntUnaryOperator indexOfChoice() { return i -> i + valueCount + 1; }

	public void compact() {
		if (enableCompaction && (compactedRanks == null || compactedChoices == null)) {
			List<ArrayVariable> newArgs = new ArrayList<>();
			newArgs.add(getArgumentVariables().get(0));

			List<ArrayVariable<Scalar>> ranks = getRanks();
			compactedRanks = new Function[ranks.size()];
			IntStream.range(0, compactedRanks.length).forEach(i -> newArgs.add(ranks.get(i)));

			List<ArrayVariable<T>> choices = getChoices();
			compactedChoices = new Function[choices.size()];
			IntStream.range(0, compactedChoices.length).forEach(i -> newArgs.add(choices.get(i)));

			newArgs.add(getDefaultValue());

			// setArguments(newArgs);
		}
	}

	@Override
	public MemoryBank<T> createDestination(int size) { return forKernel.apply(size); }

	private String getKernelIndex(int kernelIndex) {
		return getComputeContext().getLanguage().kernelIndex(kernelIndex);
	}

	private String getKernelIndex(ArrayVariable v, int kernelIndex) {
		if (kernelIndex > 0) {
			throw new UnsupportedOperationException("Only one kernel dimension is currently supported");
		}

		return kernelIndex < 0 ? "" :
				getKernelIndex(kernelIndex) + " * " + getComputeContext().getLanguage().getVariableDimName(v, kernelIndex) + " + ";
	}

	private String getValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
		String name;

		if (v instanceof ArrayVariable) {
			if (v.getProducer() instanceof ParallelProcess
					&& ((ParallelProcess) v.getProducer()).getCountLong() > 1) {
				String kernelOffset = getKernelIndex((ArrayVariable) v, kernelIndex);

				if (pos.equals("0") || pos.equals("(0)")) {
					name = v.getName() + "[" + kernelOffset + v.getName() + "Offset]";
				} else {
					name = v.getName() + "[" + kernelOffset + v.getName() + "Offset + (int) (" + pos + ")]";
				}
			} else {
				if (pos.equals("0")) {
					name = v.getName() + "[" + v.getName() + "Offset]";
				} else {
					name = v.getName() + "[" + v.getName() + "Offset + (int) (" + pos + ")]";
				}
			}
		} else {
			name = v.getName() + "[(int) (" + pos + ")]";
		}

		if (isCastEnabled() && !assignment) {
			return "(float)" + name;
		} else {
			return name;
		}
	}

	private boolean isCastEnabled() {
		return !getComputeContext().isCPU() && getComputeContext().getDataContext().getPrecision() == Precision.FP64;
	}

	public String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
		return getValueName(v, pos, assignment, isKernel() ? kernelIndex : -1);
	}

	private String getVariableValueName(Variable v, int pos) {
		return getVariableValueName(v, pos, 0);
	}

	private String getVariableValueName(Variable v, int pos, int kernelIndex) {
		return getVariableValueName(v, pos, false, kernelIndex);
	}

	private String getVariableValueName(Variable v, int pos, boolean assignment) {
		return getVariableValueName(v, pos, assignment, 0);
	}

	private String getArgumentValueName(int index, int pos, boolean assignment, int kernelIndex) {
		return getVariableValueName(getArgument(index), pos, assignment, kernelIndex);
	}

	private String getVariableValueName(Variable v, int pos, boolean assignment, int kernelIndex) {
		return getVariableValueName(v, String.valueOf(pos), assignment, kernelIndex);
	}

	private String getArgumentValueName(int index, int pos, boolean assignment) {
		return getArgumentValueName(index, pos, assignment, 0);
	}

	private static <T> Supplier[] generateArgs(List<ProducerWithRank<T, Scalar>> values, Supplier<Evaluable<? extends T>> defaultValue) {
		List<Supplier> args = new ArrayList<>();
		values.stream().map(ProducerWithRank::getRank).forEach(args::add);
		values.stream().map(ProducerWithRank::getProducer).forEach(args::add);
		args.add(defaultValue);
		return args.toArray(new Supplier[0]);
	}
}
