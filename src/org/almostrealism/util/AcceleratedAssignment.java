/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.util;

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.DynamicAcceleratedOperationAdapter;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.ScopeInputManager;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class AcceleratedAssignment<T extends MemWrapper> extends DynamicAcceleratedOperationAdapter<T> {
	private int memLength;

	public AcceleratedAssignment(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		super(result, value);
		this.memLength = memLength;
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		purgeVariables();

		IntStream.range(0, memLength)
				.mapToObj(i ->
						new Variable(getVariableValueName(getArgument(0), i), false,
							new Expression<>(Double.class,
									getVariableValueName(getArgument(1), i), getArgument(1)), getArgument(0)))
				.forEach(this::addVariable);
	}
}
