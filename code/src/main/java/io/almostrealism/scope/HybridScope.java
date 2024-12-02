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

package io.almostrealism.scope;

import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Parent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HybridScope<T> extends Scope<T> {
	private final ExplicitScope<T> explicit;
	private CodeGenerator codeGenerator;

	public HybridScope(OperationAdapter operation) {
		super(operation.getFunctionName(), operation.getMetadata());
		this.explicit = new ExplicitScope<T>(operation);

		if (operation.getComputeRequirements() != null) {
			setComputeRequirements(operation.getComputeRequirements());
		}
	}

	public HybridScope(ExplicitScope<T> explicit, CodeGenerator generator) {
		this.explicit = explicit;
		this.codeGenerator = generator;
	}

	public void setArguments(List<Argument<?>> arguments) { explicit.setArguments(arguments); }

	public void setDependencies(Collection<Variable<?, ?>> dependencies) {
		explicit.setArguments(Scope.extractArgumentDependencies(dependencies));
	}

	public ExplicitScope<T> getExplicit() {
		return explicit;
	}

	public void setSource(CodeGenerator source) {
		this.codeGenerator = source;
	}

	@Override
	public void write(CodePrintWriter w) {
		super.write(w);

		if (codeGenerator == null) {
			explicit.write(w);
		} else {
			w.println(codeGenerator.generate(this, w.getLanguage()));
		}
	}

	public Consumer<String> code() { return explicit.code(); }

	public boolean isInlineable() { return explicit.isInlineable(); }

	@Override
	public Parent<Scope<T>> generate(List<Scope<T>> children) {
		Scope<T> scope = new HybridScope<>(explicit, codeGenerator);
		scope.setName(getName());
		scope.setMetadata(getMetadata());
		scope.getChildren().addAll(children);
		return scope;
	}

	@Override
	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();
		args.addAll(explicit.arguments(Function.identity()));
		args.addAll(super.arguments(Function.identity()));
		return Scope.removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}
}
