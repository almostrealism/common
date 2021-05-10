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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Computation;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Named;
import org.almostrealism.c.OpenCLPrintWriter;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeEncoder;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.code.Variable;

import org.almostrealism.hardware.mem.MemWrapperArgumentMap;
import org.almostrealism.io.PrintWriter;

import java.util.List;

public class AcceleratedComputationOperation<T> extends DynamicAcceleratedOperation<MemWrapper> implements NameProvider {
	public static boolean enableRequiredScopes = true;

	private Compilation compilation;
	private Computation<T> computation;
	private Scope<T> scope;

	public AcceleratedComputationOperation(Computation<T> c, boolean kernel) {
		super(kernel, new ArrayVariable[0]);
		this.compilation = Compilation.CL;
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

	public void setCompilation(Compilation c) { this.compilation = c; }

	public Compilation getCompilation() { return compilation; }

	@Override
	public void addVariable(Variable v) {
		if (v.getProducer() == null) {
			throw new IllegalArgumentException("Producer must be provided for variable");
		}

		((OperationAdapter) getComputation()).addVariable(v);
	}

	@Override
	public List<Variable<?>> getVariables() {
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

	// TODO  This can probably be removed, with the superclass method used instead by simply
	//       making prepareArguments and prepareScope delegate to the computation
	@Override
	protected void prepareScope() {
		super.prepareScope();

		SupplierArgumentMap argumentMap = null;

		if (enableDestinationConsolidation) {
			argumentMap = new DestinationConsolidationArgumentMap<>();
		} else if (enableArgumentMapping) {
			argumentMap = new MemWrapperArgumentMap<>();
		}

		if (argumentMap != null) {
			getComputation().prepareArguments(argumentMap);
			this.argumentMaps.add(argumentMap);
		}

		getComputation().prepareScope(argumentMap == null ?
				DefaultScopeInputManager.getInstance() : argumentMap.getScopeInputManager());
	}

	protected void preCompile() {
		prepareScope();
		if (enableCompaction) compact();
	}

	@Override
	public Scope<T> compile() {
		preCompile();

		if (getComputation() instanceof OperationAdapter
				&& ((OperationAdapter) getComputation()).getArgsCount() > 0) {
			return compile(((OperationAdapter) getComputation()).getArgument(0));
		} else {
			return compile(null);
		}
	}

	public synchronized Scope<T> compile(Variable<T> outputVariable) {
		Computation<T> c = getComputation();
		if (outputVariable != null) c.setOutputVariable(outputVariable);
		scope = c.getScope();
		if (enableRequiredScopes) scope.convertArgumentsToRequiredScopes();
		postCompile();
		return scope;
	}

	@Override
	public void postCompile() {
		setInputs(scope.getInputs());
		setArguments(scope.getArguments());
		super.postCompile();
	}

	@Override
	public String getFunctionDefinition() {
		if (scope == null) compile();
		ScopeEncoder encoder = new ScopeEncoder(compilation.getGenerator(), Accessibility.EXTERNAL);
		return encoder.apply(scope);
	}

	@Override
	public String getBody(Variable<MemWrapper> outputVariable) {
		Scope<T> scope = compile((Variable<T>) outputVariable);
		StringBuilder buf = new StringBuilder();
		scope.write(new OpenCLPrintWriter(PrintWriter.of(buf::append)));
		return buf.toString();
	}

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
	public void destroy() {
		super.destroy();
		if (getComputation() instanceof OperationAdapter) {
			((OperationAdapter) getComputation()).destroy();
		}
	}
}
