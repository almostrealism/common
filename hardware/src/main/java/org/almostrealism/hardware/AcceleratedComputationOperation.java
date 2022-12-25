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

package org.almostrealism.hardware;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Named;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.mem.Bytes;

import java.util.List;
import java.util.function.Consumer;

public class AcceleratedComputationOperation<T> extends DynamicAcceleratedOperation<MemoryData> implements NameProvider {
	public static boolean enableRequiredScopes = true;
	public static boolean enableOperationInputAggregation = true;

	private Computation<T> computation;
	private Scope<T> scope;
	private Variable outputVariable;

	public AcceleratedComputationOperation(Computation<T> c, boolean kernel) {
		super(kernel, new ArrayVariable[0]);
		this.computation = c;
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
	public String getName() {
		if (getComputation() instanceof Named) {
			return ((Named) getComputation()).getName();
		} else {
			return super.getName();
		}
	}

	@Override
	public void addVariable(Variable v) {
		if (v.getProducer() == null) {
			throw new IllegalArgumentException("Producer must be provided for variable");
		}

		((OperationAdapter) getComputation()).addVariable(v);
	}

	@Override
	public List<Variable<?, ?>> getVariables() {
		return ((OperationAdapter) getComputation()).getVariables();
	}

	@Override
	public void purgeVariables() {
		((OperationAdapter) getComputation()).purgeVariables();
	}

	@Override
	public String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
		return getValueName(v, pos, assignment, enableKernel && isKernel() ? kernelIndex : -1);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		getComputation().prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		getComputation().prepareScope(manager);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		getComputation().resetArguments();
	}

	protected synchronized void preCompile() {
		prepareScope();
		if (enableCompaction) compact();
	}

	@Override
	public synchronized Scope<T> compile() {
		if (scope != null) {
			System.out.println("WARN: Attempting to compile an operation which was already compiled");
			return scope;
		}

		preCompile();

		if (getComputation() instanceof OperationAdapter
				&& ((OperationAdapter) getComputation()).getArgsCount() > 0) {
			return compile(((OperationAdapter) getComputation()).getArgument(0));
		} else {
			return compile(null);
		}
	}

	public synchronized Scope<T> compile(Variable<T, ?> outputVariable) {
		Computation<T> c = getComputation();
		if (outputVariable != null) c.setOutputVariable(outputVariable);
		scope = c.getScope();
		if (enableRequiredScopes) scope.convertArgumentsToRequiredScopes();
		postCompile();
		return scope;
	}

	@Override
	public synchronized void postCompile() {
		setInputs(scope.getInputs());
		setArguments(scope.getArguments());
		outputVariable = getComputation().getOutputVariable();
		super.postCompile();
	}

	@Override
	public synchronized Consumer<Object[]> getOperator() {
		if (operators == null || operators.isDestroyed()) {
			operators = Hardware.getLocalHardware().getComputeContext().deliver(scope);
		}

		return operators.get(getFunctionName(), getArgsCount());
	}

	@Override
	public Variable getOutputVariable() { return outputVariable == null ? computation.getOutputVariable() : outputVariable; }

	@Override
	public void compact() {
		if (getComputation() instanceof Compactable) {
			((Compactable) getComputation()).compact();
		}
	}

	@Override
	public boolean isStatic() {
		return getComputation() instanceof Compactable && ((Compactable) getComputation()).isStatic();
	}

	@Override
	public boolean isAggregatedInput() {
		return enableOperationInputAggregation;
	}

	@Override
	public void destroy() {
		super.destroy();
		if (getComputation() instanceof OperationAdapter) {
			((OperationAdapter) getComputation()).destroy();
		}
	}
}
