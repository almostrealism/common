/*
 * Copyright 2021 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware;

import io.almostrealism.code.Method;
import io.almostrealism.code.OperationComputationAdapter;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Operation;
import org.almostrealism.c.OpenCLPrintWriter;

import java.util.Arrays;
import java.util.function.Supplier;

public abstract class DynamicOperationComputationAdapter<T> extends OperationComputationAdapter<T, Void> implements Operation, ComputerFeatures {
	@SafeVarargs
	public DynamicOperationComputationAdapter(Supplier<Evaluable<? extends T>>... inputArgs) {
		this(inputArgs, new Object[0]);
	}

	public DynamicOperationComputationAdapter(Supplier<Evaluable<? extends T>>[] inputArgs, Object[] additionalArguments) {
		this.setInputs(Arrays.asList(AcceleratedEvaluable.producers(inputArgs, additionalArguments)));
		init();
	}

	protected String renderMethod(Method method) {
		return new OpenCLPrintWriter(null).renderMethod(method);
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public Runnable get() { return compileRunnable(this); }
}
