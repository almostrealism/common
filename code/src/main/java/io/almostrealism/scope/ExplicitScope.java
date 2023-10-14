/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.LanguageOperations;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.OperationMetadata;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ExplicitScope<T> extends Scope<T> {
	private StringBuffer code;
	private Function<LanguageOperations, String> codeGenerator;
	private List<Argument<?>> arguments;

	public ExplicitScope(OperationAdapter op) {
		this(op.getFunctionName(), op.getMetadata());
		setArguments(op.getArguments());
	}

	public ExplicitScope(String name, OperationMetadata metadata) {
		this(name, metadata, null);
	}

	public ExplicitScope(String name, OperationMetadata metadata, String code) {
		super(name, metadata);
		this.code = new StringBuffer();
		if (code != null) this.code.append(code);
	}

	public void setSource(Function<LanguageOperations, String> source) {
		this.codeGenerator = source;
	}

	public void setArguments(List<Argument<?>> arguments) { this.arguments = arguments; }

	@Override
	protected <T> List<T> arguments(Function<Argument<?>, T> mapper) {
		List<T> result = null;

		if (arguments != null) {
			result = arguments.stream()
					.map(mapper)
					.collect(Collectors.toList());
		}

		if (result == null) return super.arguments(mapper);
		return result;
	}

	public Consumer<String> code() { return code::append; }

	@Override
	public boolean isInlineable() {
		if (!getChildren().isEmpty()) return false;
		if (!getMethods().isEmpty()) return false;
		if (!getVariables().isEmpty()) return false;
		return false; // TODO  Maybe it can be inlinable
	}

	@Override
	public void write(CodePrintWriter w) {
		super.write(w);

		if (codeGenerator == null) {
			w.println(code.toString());
		} else {
			w.println(codeGenerator.apply(w.getLanguage()));
		}
	}
}
