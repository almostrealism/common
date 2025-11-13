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
import io.almostrealism.code.DefaultNameProvider;
import io.almostrealism.code.Execution;
import io.almostrealism.code.NamedFunction;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.NameProvider;
import io.almostrealism.compute.Process;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.uml.Named;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.arguments.ProcessArgumentMap;
import org.almostrealism.hardware.instructions.ComputableInstructionSetManager;
import org.almostrealism.hardware.instructions.ComputationInstructionsManager;
import org.almostrealism.hardware.instructions.ComputationScopeCompiler;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.instructions.ScopeInstructionsManager;
import org.almostrealism.hardware.instructions.ScopeSignatureExecutionKey;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.io.Describable;
import org.almostrealism.lifecycle.WeakRunnable;

import java.util.List;
import java.util.function.Supplier;

public class AcceleratedComputationOperation<T> extends AcceleratedOperation<MemoryData>
		implements Countable, Signature {

	private Computation<T> computation;
	private ComputationScopeCompiler<T> compiler;

	private ComputableInstructionSetManager<?> instructions;
	private ExecutionKey executionKey;

	public AcceleratedComputationOperation(ComputeContext<MemoryData> context, Computation<T> c, boolean kernel) {
		super(context, kernel);
		this.computation = c;
		init();
	}

	public void init() {
		if (getComputation() instanceof NamedFunction) {
			setFunctionName(((NamedFunction) getComputation()).getFunctionName());
		} else {
			setFunctionName(functionName(getComputation().getClass()));
		}
	}

	public NameProvider getNameProvider() {
		if (getComputation() instanceof NamedFunction) {
			return new DefaultNameProvider((NamedFunction) getComputation());
		}

		throw new UnsupportedOperationException();
	}

	public Computation<T> getComputation() { return computation; }

	public ComputationScopeCompiler<T> getCompiler() {
		if (compiler == null) {
			compiler = new ComputationScopeCompiler<>(getComputation(), getNameProvider());
		}

		return compiler;
	}

	@Override
	public OperationMetadata getMetadata() {
		if (computation instanceof OperationInfo) {
			return ((OperationInfo) computation).getMetadata();
		}

		throw new UnsupportedOperationException();
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
		return Named.nameOf(getComputation());
	}

	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		return getCompiler().getComputeRequirements();
	}

	@Override
	public <K extends ExecutionKey> ComputableInstructionSetManager<K> getInstructionSetManager() {
		if (instructions == null) {
			String signature = signature();

			if (ScopeSettings.enableInstructionSetReuse && signature != null) {
				DefaultComputer computer = Hardware.getLocalHardware().getComputer();

				instructions = computer.getScopeInstructionsManager(
						signature, getComputation(), getComputeContext(), this::getScope);
				((ScopeInstructionsManager) instructions)
						.addDestroyListener(new WeakRunnable<>(this, AcceleratedComputationOperation::resetInstructions));
			} else {
				instructions = new ComputationInstructionsManager(
						getComputeContext(), this::getScope);
				if (!compiler.isCompiled()) compile();
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
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		getCompiler().prepareArguments(map);
	}

	@Override
	protected void prepareScope(ScopeInputManager manager) {
		getCompiler().prepareScope(manager);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		getCompiler().prepareScope(manager, context);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		getCompiler().resetArguments();
	}

	@Override
	public Execution load() {
		Execution operator = super.load();

		if (getArguments() == null) {
			if (getComputation() instanceof Process<?,?>) {
				// If the Computation is a Process, the structure of the Process
				// tree can be used to substitute arguments from this Computation
				// for those in the shared Execution
				ScopeInstructionsManager manager = (ScopeInstructionsManager) getInstructionSetManager();
				setupArguments(manager.getScopeInputs(), manager.getScopeArguments());

				ProcessArgumentMap map = new ProcessArgumentMap(manager.getArgumentMap());
				map.putSubstitutions((Process<?,?>) getComputation());
				setEvaluator(map);
			} else {
				warn("Unable to reuse instructions for " + getFunctionName() +
						" because " + getComputation() + " is not a Process");
				compile();
			}
		}

		return operator;
	}

	protected Scope<T> getScope() {
		if (getCompiler().getScope() == null) {
			compile();
		}

		return getCompiler().getScope();
	}

	public synchronized Scope<T> compile() {
		new ExpressionCache().use(getMetadata(), () -> {
			prepareScope();
			getCompiler().compile();
			postCompile();
		});

		return getCompiler().getScope();
	}

	@Deprecated
	public void compile(ComputableInstructionSetManager<?> instructions, ExecutionKey executionKey) {
		warn("Use of deprecated compile method");
		this.instructions = instructions;
		this.executionKey = executionKey;
	}

	protected void resetInstructions() {
		instructions = null;
		executionKey = null;

		if (compiler != null) {
			compiler.destroy();
			compiler = null;
		}
	}

	public synchronized void postCompile() {
		setupArguments(getCompiler().getScope());
		getCompiler().postCompile();
	}

	protected void setupArguments(Scope<?> scope) {
		setupArguments(scope.getInputs(), scope.getArguments());
	}

	protected void setupArguments(List<Supplier<Evaluable<? extends MemoryData>>> inputs,
								  List<Argument<? extends MemoryData>> arguments) {
		setInputs(inputs);
		setArguments(arguments);
	}

	@Override
	protected AcceleratedProcessDetails getProcessDetails(MemoryBank output, Object[] args) {
		AcceleratedProcessDetails process = super.getProcessDetails(output, args);

		if (!getCompiler().isValidKernelSize(process.getKernelSize())) {
			throw new UnsupportedOperationException();
		}

		return process;
	}

	public Variable getOutputVariable() {
		return getComputation().getOutputVariable();
	}

	@Override
	public boolean isAggregatedInput() { return true; }

	@Override
	public String signature() { return getCompiler().signature(); }

	@Override
	protected void waitFor(Semaphore semaphore) {
		if (getComputeContext().isExecutorThread()) {
			throw new IllegalStateException("Attempting to block the ComputeContext executor");
		}

		super.waitFor(semaphore);
	}

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

		setInputs((List) null);

		if (getComputation() instanceof Destroyable) {
			((Destroyable) getComputation()).destroy();
		}

		if (compiler != null) {
			compiler.destroy();
			compiler = null;
		}
	}
}
