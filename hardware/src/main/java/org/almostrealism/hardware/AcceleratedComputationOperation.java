/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.NameProvider;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.collect.Shape;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.uml.Named;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.instructions.ComputableInstructionSetManager;
import org.almostrealism.hardware.instructions.ComputationInstructionsManager;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.instructions.ScopeSignatureExecutionKey;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.hardware.kernel.KernelTraversalOperationGenerator;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.io.Describable;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

public class AcceleratedComputationOperation<T> extends AcceleratedOperation<MemoryData>
		implements NameProvider, KernelStructureContext, Countable {
	public static boolean verboseCompile = SystemUtils.isEnabled("AR_HARDWARE_VERBOSE_COMPILE").orElse(false);
	public static boolean enablePostConversionSimplify = true;
	public static ScopeTimingListener timing;

	private Computation<T> computation;
	private KernelSeriesCache kernelSeriesCache;
	private KernelTraversalOperationGenerator traversalGenerator;
	private OptionalLong kernelMaximum;
	private boolean kernelStructureSupported;

	private Scope<T> scope;
	private Variable outputVariable;
	private ComputableInstructionSetManager<?> instructions;
	private ExecutionKey executionKey;

	public AcceleratedComputationOperation(ComputeContext<MemoryData> context, Computation<T> c, boolean kernel) {
		super(context, kernel, new ArrayVariable[0]);
		this.computation = c;
		this.kernelStructureSupported = true;
		init();
	}

	@Override
	public void init() {
		if (getComputation() instanceof NameProvider) {
			setFunctionName(((NameProvider) getComputation()).getFunctionName());
		} else {
			setFunctionName(functionName(getComputation().getClass()));
		}
	}

	public Computation<T> getComputation() { return computation; }

	@Override
	public OperationMetadata getMetadata() {
		return computation instanceof OperationInfo ? ((OperationInfo) computation).getMetadata() : super.getMetadata();
	}

	@Override
	public long getCountLong() {
		if (getComputation() instanceof ParallelProcess) {
			return ((ParallelProcess) getComputation()).getParallelism();
		}

		return getComputation() instanceof Countable ? ((Countable) getComputation()).getCountLong() : 1;
	}

	@Override
	public boolean isFixedCount() {
		return !(getComputation() instanceof Countable) || ((Countable) getComputation()).isFixedCount();
	}

	@Override
	public String getName() {
		if (getComputation() instanceof Named) {
			return ((Named) getComputation()).getName();
		} else {
			return super.getName();
		}
	}

	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		if (scope != null) return scope.getComputeRequirements();
		if (getComputation() instanceof OperationInfo) {
			return ((OperationInfo) getComputation()).getComputeRequirements();
		}

		return super.getComputeRequirements();
	}

	public void setKernelStructureSupported(boolean supported) {
		this.kernelStructureSupported = supported;
	}

	public boolean isKernelStructureSupported() { return kernelStructureSupported; }

	@Override
	public OptionalLong getKernelMaximum() {
		if (kernelMaximum == null) {
			kernelMaximum = isFixedCount() ? OptionalLong.of(getCountLong()) : OptionalLong.empty();
		}

		return kernelMaximum;
	}

	@Override
	public KernelSeriesProvider getSeriesProvider() {
		return isKernelStructureSupported() ? kernelSeriesCache : null;
	}

	@Override
	public KernelTraversalProvider getTraversalProvider() {
		return isKernelStructureSupported() ? traversalGenerator : null;
	}

	@Override
	public <K extends ExecutionKey> ComputableInstructionSetManager<K> getInstructionSetManager() {
		if (instructions == null && scope != null) {
			if (ScopeSettings.enableInstructionSetReuse && getMetadata().getSignature() != null) {
				DefaultComputer computer = Hardware.getLocalHardware().getComputer();

				instructions = computer.getScopeInstructionsManager(
						getMetadata().getSignature(), getComputeContext(), () -> scope);
			} else {
				instructions = new ComputationInstructionsManager(
						getComputeContext(), scope);
			}
		}

		return (ComputableInstructionSetManager) instructions;
	}

	@Override
	public ExecutionKey getExecutionKey() {
		if (executionKey != null)
			return executionKey;

		String signature = getMetadata().getSignature();

		if (ScopeSettings.enableInstructionSetReuse && signature != null) {
			return new ScopeSignatureExecutionKey(signature);
		} else {
			return new DefaultExecutionKey(getFunctionName(), getArgsCount());
		}
	}

	@Override
	protected int getOutputArgumentIndex() {
		return getInstructionSetManager().getOutputArgumentIndex(getExecutionKey());
	}

	@Override
	public void addVariable(ExpressionAssignment<?> v) {
		if (v.getProducer() == null) {
			throw new IllegalArgumentException("Producer must be provided for variable");
		}

		((OperationAdapter) getComputation()).addVariable(v);
	}

	@Override
	public List<ExpressionAssignment<?>> getVariables() {
		return ((OperationAdapter) getComputation()).getVariables();
	}

	@Override
	public void purgeVariables() {
		((OperationAdapter) getComputation()).purgeVariables();
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		getComputation().prepareArguments(map);
	}

	@Override
	protected void prepareScope(ScopeInputManager manager) {
		prepareScope(manager, this);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		getComputation().prepareScope(manager, context);

		this.kernelSeriesCache = KernelSeriesCache.create(getComputation(),
				data -> manager.argumentForInput(this).apply(() -> new Provider<>(data)));
		this.traversalGenerator = KernelTraversalOperationGenerator.create(getComputation(),
				data -> manager.argumentForInput(this).apply((Supplier<Evaluable<?>>) data));
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		getComputation().resetArguments();
		this.kernelMaximum = null;
	}

	protected void setupOutputVariable() {
		Variable<T, ?> output = null;

		// TODO  Is this even necessary?
		if (getComputation() instanceof OperationAdapter
				&& ((OperationAdapter) getComputation()).getArgsCount() > 0) {
			OperationAdapter<T, ?> c = (OperationAdapter<T, ?>) getComputation();
			output = c.getArgumentForInput(c.getInputs().get(0));
		}

		if (output != null) {
			getComputation().setOutputVariable(output);
		}
	}

	@Override
	public synchronized Scope<T> compile() {
		if (scope != null) {
			warn("Attempting to compile an operation which was already compiled");
			return scope;
		}

		if (verboseCompile) log("Compiling " + getFunctionName());

		try {
			return new ExpressionCache().use(getMetadata(), () -> {
				prepareScope();
				setupOutputVariable();

				Computation<T> c = getComputation();

				long start = System.nanoTime();

				scope = c.getScope(this);
				if (timing != null) {
					timing.recordDuration(getMetadata(), scope.getMetadata(),
							"getScope", System.nanoTime() - start);
				}

				if (!enablePostConversionSimplify)
					scope = scope.simplify(this);

				start = System.nanoTime();
				scope.convertArgumentsToRequiredScopes(this);
				if (timing != null) {
					timing.recordDuration(getMetadata(), scope.getMetadata(),
							"convertRequired", System.nanoTime() - start);
				}

				if (enablePostConversionSimplify)
					scope = scope.simplify(this);

				postCompile();

				if (verboseCompile) log("Done compiling " + getFunctionName());
				return scope;
			});
		} catch (Exception e) {
			throw new HardwareException("Cannot compile " + getName(), e);
		}
	}

	public void compile(ComputableInstructionSetManager<?> instructions, ExecutionKey executionKey) {
		this.instructions = instructions;
		this.executionKey = executionKey;
	}

	@Override
	public synchronized void postCompile() {
		setInputs(scope.getInputs());
		setArguments(scope.getArguments());
		outputVariable = getComputation().getOutputVariable();

		if (getComputation() instanceof Shape) {
			TraversalPolicy shape = scope.getMetadata().getShape();

			if (shape == null) {
				warn("Missing TraversalPolicy for Scope metadata");
				scope.setMetadata(scope.getMetadata().withShape(((Shape<?>) getComputation()).getShape()));
			} else if (!shape.equals(((Shape<?>) getComputation()).getShape())) {
				throw new IllegalArgumentException("Shape mismatch between Scope metadata and Computation");
			}
		}

		// kernelSeriesCache.destroy();
		super.postCompile();
	}

	@Override
	public boolean isCompiled() { return scope != null || getInstructionSetManager() != null; }

	@Override
	protected AcceleratedProcessDetails getProcessDetails(MemoryBank output, Object[] args) {
		AcceleratedProcessDetails process = super.getProcessDetails(output, args);
		if ((getKernelMaximum().isPresent() && process.getKernelSize() !=
					getKernelMaximum().getAsLong()) ||
				(kernelSeriesCache.getMaximumLength().isPresent() && process.getKernelSize() !=
					kernelSeriesCache.getMaximumLength().getAsInt())) {
			throw new UnsupportedOperationException();
		}

		return process;
	}

	@Override
	public Variable getOutputVariable() {
		return outputVariable == null ? computation.getOutputVariable() : outputVariable;
	}

	@Override
	public boolean isAggregatedInput() { return true; }

	@Override
	public String describe() {
		if (getComputation() instanceof Describable) {
			return ((Describable) getComputation()).describe();
		} else {
			return super.describe();
		}
	}

	@Override
	public void destroy() {
		super.destroy();

		// If there is no Scope, then the instructions were created
		// somewhere else and do not need to be destroyed
		if (scope != null) {
			if (instructions != null) {
				instructions.destroy();
				instructions = null;
			}

			scope = null;
		}

		setInputs((List) null);
		outputVariable = null;

		if (getComputation() instanceof Destroyable) {
			((Destroyable) getComputation()).destroy();
		}
	}

	public static void clearTimes() {
		KernelTraversalProvider.timing.clear();
	}

	public static void printTimes() {
		printTimes(false);
	}

	public static void printTimes(boolean verbose) {
		if (verbose || KernelTraversalProvider.timing.getTotal() > 10) {
			KernelTraversalProvider.timing.print();
		}
	}
}
