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

package org.almostrealism.generated;

import io.almostrealism.code.Computation;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.NamedFunction;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeComputationEvaluable;
import org.almostrealism.hardware.jni.NativeSupport;

public abstract class BaseGeneratedProducer<T extends MemoryData> implements NativeSupport<NativeComputationEvaluable<T>> {
	private Computation<T> computation;

	public BaseGeneratedProducer(Computation<T> computation) {
		this.computation = computation;
		initNative();
	}

	@Override
	public void setFunctionName(String name) {
		((NamedFunction) computation).setFunctionName(name);
	}

	@Override
	public String getFunctionName() {
		return ((NamedFunction) computation).getFunctionName();
	}

	@Override
	public Variable getOutputVariable() {
		return ((NameProvider) computation).getOutputVariable();
	}

	@Override
	public String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
		return ((NameProvider) computation).getVariableValueName(v, pos, assignment, kernelIndex);
	}

	@Override
	public NativeComputationEvaluable<T> get() {
		return new NativeComputationEvaluable<T>(computation, this);
	}
}
