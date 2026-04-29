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

package io.almostrealism.code;

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.function.Supplier;

/**
 * An {@link ArgumentMap} that reuses the output variable of a {@link Computation} as the argument
 * variable for that computation, avoiding unnecessary memory copies.
 *
 * <p>When a computation exposes an output variable via {@link Computation#getOutputVariable()},
 * this map returns that variable directly instead of creating a new one. This is the standard
 * argument map used when one computation's output feeds directly into another's input.</p>
 *
 * @param <S> the supplier type (the input side)
 * @param <A> the array element type
 *
 * @see SupplierArgumentMap
 * @see Computation#getOutputVariable()
 */
public class OutputVariablePreservationArgumentMap<S, A> extends SupplierArgumentMap<S, A> {
	/**
	 * If the provided key is a {@link Computation}, reuse the {@link Variable} it
	 * exposes via {@link Computation#getOutputVariable()}. Otherwise, delegate to
	 * the superclass method.
	 */
	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		if (key instanceof Computation) {
			ArrayVariable<A> out = (ArrayVariable<A>) ((Computation) key).getOutputVariable();
			if (out != null) return out;
		}

		return super.get(key, p);
	}
}
