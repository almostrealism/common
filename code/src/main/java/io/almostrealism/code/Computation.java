/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Computable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

/**
 * A {@link Computation} is a {@link Computable} that describes a computational
 * process specifically via a {@link Scope}.
 *
 * @param <T>  The type of the ultimate result of computation.
 *
 * @author  Michael Murray
 */
public interface Computation<T> extends
		Computable, ScopeLifecycle, OutputSupport, ConsoleFeatures {
	Console console = Scope.console.child();

	/**
	 * Return a {@link Scope} containing the {@link Variable}s
	 * and {@link Method}s necessary to compute the output of
	 * this {@link Computation}.
	 */
	Scope<T> getScope(KernelStructureContext context);

	@Override
	default Console console() { return console; }
}
