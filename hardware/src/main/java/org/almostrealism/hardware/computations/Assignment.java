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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.Variable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import org.almostrealism.hardware.MemWrapper;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class Assignment<T extends MemWrapper> extends DynamicOperationComputationAdapter<T> {
	private final int memLength;

	public Assignment(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		super(result, value);
		this.memLength = memLength;
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		purgeVariables();

		IntStream.range(0, memLength)
				.mapToObj(i ->
						new Variable(getArgument(0).get(i).getExpression(), false,
									getArgument(1).get(i), getArgument(0)))
				.forEach(this::addVariable);
	}
}