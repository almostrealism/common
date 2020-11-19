/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.c.OpenCLPrintWriter;
import io.almostrealism.code.Argument;
import io.almostrealism.code.ComputationOperationAdapter;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeEncoder;
import io.almostrealism.code.Variable;
import org.almostrealism.io.PrintWriter;
import org.almostrealism.relation.Computation;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Compactable;
import org.almostrealism.util.Named;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class AcceleratedComputationOperation<T> extends DynamicAcceleratedOperation<MemWrapper> implements NameProvider {
	private Computation<T> computation;

	public AcceleratedComputationOperation(Computation<T> c, boolean kernel) {
		super(kernel, new Argument[0]);
		this.computation = c;
		init();
	}

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
	public String getDefaultAnnotation() { return "__global"; }

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
	public String getVariableValueName(Variable v, int pos, boolean assignment, int kernelIndex) {
		return getValueName(v, pos, assignment, (enableKernel && isKernel()) ? kernelIndex : -1);
	}

	@Override
	public Scope<T> compile(NameProvider p) {
		return compile(p, ((OperationAdapter) getComputation()).getArgument(0));
	}

	public Scope<T> compile(Variable<T> outputVariable) {
		return compile(this, outputVariable);
	}

	public Scope<T> compile(NameProvider p, Variable<T> outputVariable) {
		Scope<T> scope = getComputation().getScope(p.withOutputVariable(outputVariable));
		setArguments(scope.getArguments());
		return scope;
	}

	@Override
	public String getFunctionDefinition() {
		Scope<T> scope = compile();
		ScopeEncoder encoder = new ScopeEncoder(OpenCLPrintWriter::new);
		return encoder.apply(scope);
	}

	@Override
	public String getBody(Variable<MemWrapper> outputVariable, List<Variable<?>> existingVariables) {
		Scope<T> scope = compile((Variable<T>) outputVariable);
		StringBuffer buf = new StringBuffer();
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
}
