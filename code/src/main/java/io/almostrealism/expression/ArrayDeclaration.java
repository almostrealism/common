/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.expression;

import io.almostrealism.code.Statement;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;

import java.util.List;

public class ArrayDeclaration<T> implements Statement<ArrayDeclaration<T>> {
	private Class<T> type;
	private String name;
	private Expression<?> size;

	public ArrayDeclaration(Class<T> type, String name, Expression<?> size) {
		this.type = type;
		this.name = name;
		this.size = size;
	}

	public Class<T> getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public Expression<?> getSize() {
		return size;
	}

	@Override
	public String getStatement(LanguageOperations lang) {
		return lang.declaration(type, name, null, size.getExpression(lang));
	}

	@Override
	public List<Variable<?, ?>> getDependencies() {
		return size.getDependencies();
	}

	@Override
	public ArrayDeclaration<T> simplify(KernelStructureContext context) {
		return new ArrayDeclaration<>(type, name, size.simplify(context));
	}
}
