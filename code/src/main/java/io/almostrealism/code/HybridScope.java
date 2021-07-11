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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HybridScope<T> extends Scope<T> {
	private final ExplicitScope<T> explicit;

	public HybridScope(OperationAdapter operation) {
		super(operation.getFunctionName());
		this.explicit = new ExplicitScope<T>(operation);
	}

	public HybridScope(ExplicitScope<T> explicit) {
		this.explicit = explicit;
	}

	public void setArguments(List<Argument<?>> arguments) { explicit.setArguments(arguments); }

	@Override
	public void write(CodePrintWriter w) {
		super.write(w);
		explicit.write(w);
	}

	public Consumer<String> code() { return explicit.code(); }

	@Override
	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();
		args.addAll(explicit.arguments(Function.identity()));
		args.addAll(super.arguments(Function.identity()));
		return Scope.removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}
}
