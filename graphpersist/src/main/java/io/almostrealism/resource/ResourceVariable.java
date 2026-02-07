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

package io.almostrealism.resource;

import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Variable;

/**
 * A {@link ResourceVariable} is a {@link Variable} implementation that takes data
 * from a {@link Resource} and stores it in a variable.
 *
 * @param <T>  Type of the underlying data.
 */
public class ResourceVariable<T> extends Variable<T, Variable<T, ?>> {
	private final Resource<T> res;

	/**
	 * Create a {@link ResourceVariable} using the data from the specified {@link Resource}.
	 *
	 * @see  Resource#getData()
	 */
	public ResourceVariable(String name, Resource<T> r) {
		super(name, null, null, () -> new Provider<>(r.getData()));
		this.res = r;
	}

	public Resource<T> getResource() { return res; }
}
