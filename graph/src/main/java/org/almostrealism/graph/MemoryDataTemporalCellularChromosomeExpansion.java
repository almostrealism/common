/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelList;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Factor;
import org.almostrealism.heredity.Gene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class MemoryDataTemporalCellularChromosomeExpansion<T extends MemoryBank<O>,
		I extends MemoryData,
		O extends MemoryData>
		extends TemporalCellularChromosomeExpansion<T, I, O> implements CodeFeatures {
	private final Class<O> type;
	private List<KernelOrValue> kernels;
	private int outputMemLength, inputGenes, inputFactors;
	private IntFunction<MemoryBank<O>> bankProvider;
	private BiFunction<Integer, Integer, MemoryBank<T>> tableProvider;

	public MemoryDataTemporalCellularChromosomeExpansion(Class<O> type, Chromosome<I> source, int outputMemLength,
														 IntFunction<MemoryBank<O>> bankProvider,
														 BiFunction<Integer, Integer, MemoryBank<T>> tableProvider,
														 int inputGenes, int inputFactors) {
		super(source);
		if (inputGenes <= 0) throw new IllegalArgumentException();
		this.type = type;
		this.outputMemLength = outputMemLength;
		this.inputGenes = inputGenes;
		this.inputFactors = inputFactors;
		this.bankProvider = bankProvider;
		this.tableProvider = tableProvider;
		this.kernels = new ArrayList<>();
	}

	@Override
	public void setTimeline(T timeline) { kernels.forEach(k -> k.setInput(timeline)); }

	public KernelList<O> getKernelList(int factor) {
		return Objects.requireNonNull(kernels.get(factor)).getKernels();
	}

	@Override
	public int getFactorCount() { return kernels.size(); }

	public void addFactor(Function<Gene<O>, Producer<O>> value) {
		this.kernels.add(new KernelOrValue(value));
	}

	public void addFactor(BiFunction<Producer<MemoryBank<O>>, Producer<O>, ProducerComputation<O>> computation) {
		this.kernels.add(new KernelOrValue(new KernelList(bankProvider, tableProvider, computation, inputGenes, inputFactors)));
	}

	@Override
	protected Factor<O> factor(int pos, int factor, Gene<O> gene) {
		if (kernels.get(factor).isKernel()) return super.factor(pos, factor, gene);
		return kernels.get(factor).getFactor(gene);
	}

	@Override
	protected Cell<O> cell(int pos, int factor, Gene<O> gene) {
		kernels.get(factor).setParameters(pos, gene);
		return kernels.get(factor).cell(pos);
	}

	@Override
	protected Supplier<Runnable> process() {
		OperationList op = kernels.stream()
				.map(KernelOrValue::getKernels)
				.filter(Objects::nonNull)
				.collect(OperationList.collector());

		Runnable run = op.get();

		return () -> () -> {
			if (cc().isKernelSupported()) {
				run.run();
			} else {
				cc(() -> op.get().run(), ComputeRequirement.CL);
			}
		};
	}

	protected abstract Cell<O> cell(T data);

	protected abstract Producer<T> parameters(Gene<O> gene);

	@Override
	protected Function<Producer<O>, Receptor<O>> assignment() {
		return p -> protein -> new Assignment<>(outputMemLength, p, protein);
	}

	@Override
	protected BiFunction<Producer<O>, Producer<O>, Producer<O>> combine() {
		return (a, b) -> a;
	}

	protected class KernelOrValue {
		private KernelList<O> kernels;
		private Function<Gene<O>, Producer<O>> value;

		public KernelOrValue(KernelList<O> k) { this.kernels = k; }

		public KernelOrValue(Function<Gene<O>, Producer<O>> v) { this.value = v; }

		public void setInput(T input) {
			if (isKernel()) kernels.setInput(input);
		}

		public boolean isKernel() { return kernels != null; }

		public KernelList<O> getKernels() { return kernels; }

		public void setParameters(int pos, Gene<O> parameters) {
			if (isKernel()) kernels.setParameters(pos, parameters(parameters));
		}

		public Cell<O> cell(int pos) {
			if (isKernel()) {
				return MemoryDataTemporalCellularChromosomeExpansion.this.cell((T) kernels.valueAt(pos));
			} else {
				throw new UnsupportedOperationException();
			}
		}

		public Factor<O> getFactor(Gene<O> gene) {
			return protein -> combine().apply(value.apply(gene), protein);
		}
	}
}
