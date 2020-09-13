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

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public abstract class DynamicAcceleratedProducer<T extends MemWrapper> extends AcceleratedComputation<T> {
	private boolean enableComputationEncoding;

	public DynamicAcceleratedProducer(Producer<?>... inputArgs) {
		super(inputArgs);
	}

	public DynamicAcceleratedProducer(Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(inputArgs, additionalArguments);
	}

	public DynamicAcceleratedProducer(boolean kernel, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(kernel, inputArgs, additionalArguments);
	}

	public boolean isEnableComputationEncoding() {
		return enableComputationEncoding;
	}

	public void setEnableComputationEncoding(boolean enableComputationEncoding) {
		this.enableComputationEncoding = enableComputationEncoding;
	}

	@Override
	public String getFunctionDefinition() {
		if (enableComputationEncoding) {
			return super.getFunctionDefinition();
		} else {
			StringBuffer buf = new StringBuffer();
			buf.append("__kernel void " + getFunctionName() + "(");
			buf.append(getFunctionArgsDefinition());
			buf.append(") {\n");
			buf.append(getBody(i -> getArgumentValueName(0, i), new ArrayList<>()));
			buf.append("}");
			return buf.toString();
		}
	}

	protected String getFunctionArgsDefinition() {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < getInputProducers().length; i++) {
			buf.append("__global ");
			if (i != 0) buf.append("const ");
			buf.append(getNumberType());
			buf.append(" *");
			if (getInputProducers()[i].getName() == null) {
				throw new IllegalArgumentException("Null name for Argument " + i);
			}

			buf.append(getInputProducers()[i].getName());
			buf.append(", ");
		}

		for (int i = 0; i < getInputProducers().length; i++) {
			buf.append("const int ");
			buf.append(getInputProducers()[i].getName());
			buf.append("Offset");
			buf.append(", ");
		}

		for (int i = 0; i < getInputProducers().length; i++) {
			buf.append("const int ");
			buf.append(getInputProducers()[i].getName());
			buf.append("Size");
			if (i < (getArgsCount() - 1)) buf.append(", ");
		}

		return buf.toString();
	}

	public abstract String getBody(Function<Integer, String> outputVariable, List<Variable> existingVariables);

	@Override
	public Scope<T> getScope(NameProvider provider) {
		throw new RuntimeException("Not implemented");
	}
}
