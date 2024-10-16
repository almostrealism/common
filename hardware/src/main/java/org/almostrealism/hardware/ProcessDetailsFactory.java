/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.Computation;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factory;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataDestination;
import org.almostrealism.io.SystemUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class ProcessDetailsFactory<T> implements Factory<AcceleratedProcessDetails>, Countable {
	public static boolean enableArgumentKernelSize = true;
	public static boolean enableKernelDestination = true;
	public static boolean enableConstantCache = true;
	public static boolean enableKernelSizeWarnings = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_SIZE_WARNINGS").orElse(false);

	private boolean kernel;
	private boolean fixedCount;
	private int count;

	private List<ArrayVariable<? extends T>> arguments;
	private ArrayVariable<?> outputVariable;

	private ThreadLocal<CreatedMemoryData> created;
	private MemoryProvider target;
	private AcceleratedProcessDetails.TempMemoryFactory tempFactory;

	private MemoryBank output;
	private Object args[];
	private boolean allMemoryData;
	private MemoryData memoryDataArgs[];

	private long kernelSize;
	private Map<ArrayVariable<?>, MemoryData> mappings;
	private MemoryData kernelArgs[];
	private Evaluable kernelArgEvaluables[];
	private Evaluable kernelArgDestinations[];

	public ProcessDetailsFactory(boolean kernel, boolean fixedCount, int count,
								 List<ArrayVariable<? extends T>> arguments,
								 Variable<?, ?> outputVariable,
								 ThreadLocal<CreatedMemoryData> created,
								 MemoryProvider target,
								 AcceleratedProcessDetails.TempMemoryFactory tempFactory) {
		this.kernel = kernel;
		this.fixedCount = fixedCount;
		this.count = count;

		this.arguments = arguments;
		this.outputVariable = (ArrayVariable<?>) outputVariable;

		this.created = created;
		this.target = target;
		this.tempFactory = tempFactory;
	}

	public boolean isKernel() { return kernel; }
	public boolean isFixedCount() { return fixedCount; }
	public long getCountLong() { return count; }

	public ProcessDetailsFactory init(MemoryBank output, Object args[]) {
		if (kernelArgEvaluables == null || output != this.output || !Arrays.equals(args, this.args, (a, b) -> a == b ? 0 : 1)) {
			this.output = output;
			this.args = args;

			allMemoryData = args.length <= 0 || Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty();
			if (allMemoryData) memoryDataArgs = Stream.of(args).map(MemoryData.class::cast).toArray(MemoryData[]::new);

			if (!isKernel()) {
				kernelSize = 1;
			} else if (!enableArgumentKernelSize && isFixedCount()) {
				kernelSize = getCount();
			} else if (output != null) {
				kernelSize = Math.max(output.getCountLong(), getCountLong());
			} else if (enableArgumentKernelSize && args.length > 0 && allMemoryData && ((MemoryBank) args[0]).getCountLong() > getCount()) {
				kernelSize = ((MemoryBank) args[0]).getCountLong();
			} else if (isFixedCount()) {
				kernelSize = getCount();
			} else {
				kernelSize = -1;
			}

			mappings = output == null ? Collections.emptyMap() :
					Collections.singletonMap(outputVariable, output);

			kernelArgs = new MemoryData[arguments.size()];
			kernelArgEvaluables = new Evaluable[arguments.size()];
			kernelArgDestinations = new Evaluable[arguments.size()];

			/*
			 * In the first pass, kernel size is inferred from Producer arguments that
			 * reference an Evaluable argument.
			 */
			i:
			for (int i = 0; i < arguments.size(); i++) {
				if (arguments.get(i) == null) continue i;

				if (mappings.containsKey(arguments.get(i))) {
					kernelArgs[i] = mappings.get(arguments.get(i));
					continue i;
				} else {
					int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));

					if (refIndex >= 0) {
						kernelArgs[i] = (MemoryData) args[refIndex];
					}
				}

				if (kernelSize > 0) continue i;

				// If the kernel size can be inferred from this operation argument
				// capture it from the argument to the evaluation
				if (kernelArgs[i] instanceof MemoryBank && ((MemoryBank<?>) kernelArgs[i]).getCountLong() > 1) {
					kernelSize = ((MemoryBank<?>) kernelArgs[i]).getCountLong();
				}
			}

			i: for (int i = 0; i < arguments.size(); i++) {
				if (kernelArgs[i] != null) continue i;

				kernelArgEvaluables[i] = ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());
				if (kernelArgEvaluables[i] == null) {
					throw new UnsupportedOperationException();
				} else if (enableConstantCache && kernelSize > 0 && kernelArgEvaluables[i].isConstant()) {
					kernelArgs[i] = (MemoryData) kernelArgEvaluables[i].evaluate(args);
				}
			}

			i: for (int i = 0; i < arguments.size(); i++) {
				if (kernelArgDestinations[i] != null) continue i;

				Supplier<?> p = arguments.get(i).getProducer();
				if (p instanceof ProducerComputationBase<?,?>) {
					kernelArgDestinations[i] = ((ProducerComputationBase<?,?>) p).getDestination();
				}
			}

			/*
			 * If the kernel size is still not known, the kernel size will be 1.
			 */
			if (kernelSize < 0) {
				if (enableKernelSizeWarnings)
					System.out.println("WARN: Could not infer kernel size, it will be set to " + getCount());
				kernelSize = getCount();
			}
		}

		return this;
	}

	public AcceleratedProcessDetails construct() {
		MemoryData kernelArgs[] = new MemoryData[arguments.size()];

		for (int i = 0; i < kernelArgs.length; i++) {
			if (this.kernelArgs[i] != null) kernelArgs[i] = this.kernelArgs[i];
		}

		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			if (!(kernelArgEvaluables[i] instanceof KernelizedEvaluable) ||
					(kernelArgEvaluables[i] instanceof HardwareEvaluable &&
							!((HardwareEvaluable) kernelArgEvaluables[i]).isKernel())) {
				long s = System.nanoTime();
				Object o = kernelArgEvaluables[i].evaluate(args);
				if (!(o instanceof MemoryData))
					throw new IllegalArgumentException();

				kernelArgs[i] = (MemoryData) o;

				AcceleratedOperation.nonKernelEvalMetric.addEntry(arguments.get(i).getProducer(), System.nanoTime() - s);
			}
		}

		long start = System.nanoTime();

		int size = Math.toIntExact(kernelSize);

		/*
		 * In the final pass, kernel arguments are evaluated in a way that ensures the
		 * result is compatible with the kernel size inferred earlier.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			if (enableKernelDestination && kernelArgEvaluables[i] instanceof KernelizedEvaluable) {
				kernelArgs[i] = kernelArgDestinations[i] == null ?
						(MemoryData) kernelArgEvaluables[i].createDestination(size) :
						(MemoryData) kernelArgDestinations[i].createDestination(size);

				long time = System.nanoTime() - start; start = System.nanoTime();
				AcceleratedOperation.kernelCreateMetric.addEntry(kernelArgEvaluables[i], time);

				if (created.get() != null) {
					created.get().add(kernelArgs[i]);
				} else {
					Heap.addCreatedMemory(kernelArgs[i]);
				}

				kernelArgEvaluables[i].into(kernelArgs[i]).evaluate(memoryDataArgs);

				AcceleratedOperation.evaluateKernelMetric.addEntry(System.nanoTime() - start); start = System.nanoTime();
			} else {
				kernelArgs[i] = (MemoryData) kernelArgEvaluables[i].evaluate(args);
				AcceleratedOperation.evaluateMetric.addEntry(System.nanoTime() - start); start = System.nanoTime();
			}
		}

		return new AcceleratedProcessDetails(kernelArgs, target, tempFactory, size);
	}

	@Deprecated
	private void reviewDestination(Evaluable e) {
		MemoryDataDestination dest = null;

		if (e instanceof MemoryDataDestination) {
			dest = (MemoryDataDestination) e;
		} else if (e instanceof HardwareEvaluable) {
			Evaluable d = ((HardwareEvaluable<?>) e).getDestination();
			if (d instanceof MemoryDataDestination) {
				dest = (MemoryDataDestination) d;
			}
		}

		if (dest == null) {
			throw new UnsupportedOperationException();
		}
	}

	private static int getProducerArgumentReferenceIndex(Variable<?, ?> arg) {
		if (arg.getProducer() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) arg.getProducer()).getReferencedArgumentIndex();
		}

		if (arg.getProducer() instanceof Delegated && ((Delegated) arg.getProducer()).getDelegate() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) ((Delegated) arg.getProducer()).getDelegate()).getReferencedArgumentIndex();
		}

		if (!(arg.getProducer() instanceof AcceleratedComputationOperation)) return -1;

		Computation c = ((AcceleratedComputationOperation) arg.getProducer()).getComputation();
		if (c instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) c).getReferencedArgumentIndex();
		}

		return -1;
	}
}
