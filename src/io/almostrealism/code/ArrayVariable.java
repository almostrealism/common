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

package io.almostrealism.code;

import io.almostrealism.code.expressions.InstanceReference;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.NameProvider;

import java.util.function.Supplier;

public class ArrayVariable<T> extends Variable implements Array<T> {
	private NameProvider names;

	public ArrayVariable(NameProvider np, String name, Supplier<Evaluable<? extends T>> producer) {
		this(np, name, null, null, producer);
	}

	public ArrayVariable(NameProvider np, String name, String annotation, Class<T> type, Supplier<Evaluable<? extends T>> p) {
		super(name, annotation, type, p);
		this.names = np;

		if ("f_scalarFromVector_2_l1".equals(name)) {
			System.out.println("!");
		}
	}

	@Override
	public InstanceReference<T> get(String pos) {
		return new InstanceReference(new Variable<T>(names.getVariableValueName(this, pos), false, this));
	}
}
