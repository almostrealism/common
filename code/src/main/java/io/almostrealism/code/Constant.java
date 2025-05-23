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

package io.almostrealism.code;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Scope;

import java.util.Collection;
import java.util.Collections;

public class Constant<T> implements Operator<T> {
	private T v;

	public Constant(T v) { this.v = v; }

	@Override
	public Evaluable<T> get() {
		return args -> v;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Scope<T> s = new Scope<>();
		// TODO  This is not correct
		// s.getVariables().add(new Variable(v.getClass().getSimpleName(), v));
		return s;
	}
}
