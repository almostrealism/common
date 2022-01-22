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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.function.Function;
import java.util.function.Supplier;

public interface ArgumentProvider {
	boolean enableOutputVariableDelegation = true;
	boolean enableArgumentPostProcessing = false;

	<T> ArrayVariable<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input, ArrayVariable<T> delegate, int delegateOffset);

	default <T> Function<Supplier<Evaluable<? extends T>>, ArrayVariable<T>> argumentForInput(NameProvider p) {
		return input -> {
			if (input == null) {
				return null;
			} else {
				ArrayVariable<T> arg = getArgument(p, input, null, -1);
				if (enableArgumentPostProcessing) processOutputVariableDelegation(arg);
				return arg;
			}
		};
	}

	static void processOutputVariableDelegation(ArrayVariable arg) {
		if (arg.getProducer() instanceof Computation && ((Computation) arg.getProducer()).getOutputVariable() != null) {
			Variable<?, ?> output = ((Computation) arg.getProducer()).getOutputVariable();

			// New variables should not be created when referencing the result of another computation
			// as this introduces the need to copy the result somewhere unnecessarily
			// Instead the resulting variable will delegate directly to the output variable
			if (enableOutputVariableDelegation) {
				arg.setDelegate(((ArrayVariable<?>) output).getRootDelegate());
				arg.setDelegateOffset(((ArrayVariable<?>) output).getOffset());
			} else {
				arg.setName(output.getName());
			}
		}
	}
}
