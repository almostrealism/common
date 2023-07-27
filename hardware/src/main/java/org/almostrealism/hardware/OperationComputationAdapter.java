/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.ComputationBase;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Method;
import org.almostrealism.c.OpenCLPrintWriter;

import java.util.function.Supplier;

public abstract class OperationComputationAdapter<T> extends ComputationBase<T, Void, Runnable> implements OperationComputation<Void>, ComputerFeatures {
	@SafeVarargs
	public OperationComputationAdapter(Supplier<Evaluable<? extends T>>... inputArgs) {
		this.setInputs(inputArgs);
		init();
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	protected String renderMethod(Method method) {
		return new OpenCLPrintWriter(null).renderMethod(method);
	}

	@Override
	public Runnable get() {
		Runnable r = compileRunnable(this);
		if (r instanceof OperationAdapter) {
			((OperationAdapter) r).compile();
		}
		return r;
	}

	@Deprecated
	public Runnable getKernel() {
		Runnable r = Hardware.getLocalHardware().getComputeContext().getComputer().compileRunnable(this, true);
		if (r instanceof OperationAdapter) {
			((OperationAdapter) r).compile();
		}
		return r;
	}
}
