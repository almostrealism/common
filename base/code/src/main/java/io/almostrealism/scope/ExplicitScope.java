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

import io.almostrealism.code.ComputableBase;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationMetadata;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A {@link Scope} whose body is provided as a raw code string or a
 * {@link Consumer} callback, rather than being built from child scopes
 * and expression trees.
 *
 * <p>This class is deprecated in favour of {@link HybridScope}, which
 * combines an explicit code segment with a generated child scope and
 * therefore supports richer composition.</p>
 *
 * @param <T> the return type of the scope
 * @deprecated Use {@link HybridScope} instead.
 */
@Deprecated
public class ExplicitScope<T> extends Scope<T> {
	/** The raw code accumulated for this scope's body. */
	private StringBuffer code;

	/** An optional callback that writes the scope body to a {@link CodePrintWriter}. */
	private Consumer<CodePrintWriter> writer;

	/** The explicit list of kernel arguments, or {@code null} to inherit from the parent scope. */
	private List<Argument<?>> arguments;

	/**
	 * Creates an {@link ExplicitScope} from a {@link ComputableBase}, using its function name,
	 * metadata, and arguments.
	 *
	 * @param op the computable operation that provides the name, metadata, and arguments
	 */
	public ExplicitScope(ComputableBase op) {
		this(op.getFunctionName(), op.getMetadata());
		setArguments(op.getArguments());
	}

	/**
	 * Creates an empty {@link ExplicitScope} with no initial code.
	 *
	 * @param name     the function name for this scope
	 * @param metadata operation metadata associated with this scope
	 */
	public ExplicitScope(String name, OperationMetadata metadata) {
		this(name, metadata, null);
	}

	/**
	 * Creates an {@link ExplicitScope} with optional initial code.
	 *
	 * @param name     the function name for this scope
	 * @param metadata operation metadata associated with this scope
	 * @param code     the initial code string, or {@code null} for an empty body
	 */
	public ExplicitScope(String name, OperationMetadata metadata, String code) {
		super(name, metadata);
		this.code = new StringBuffer();
		if (code != null) this.code.append(code);
	}

	/**
	 * Sets a callback that writes the scope body directly to a {@link CodePrintWriter}.
	 * When set, this takes precedence over any code accumulated via {@link #code()}.
	 *
	 * @param writer the writer callback; may be {@code null} to disable
	 */
	public void setWriter(Consumer<CodePrintWriter> writer) {
		this.writer = writer;
	}

	/**
	 * Sets the explicit list of kernel arguments for this scope, replacing the arguments
	 * that would otherwise be inherited from the parent {@link Scope}.
	 *
	 * @param arguments the argument list to use; may be {@code null} to restore inheritance
	 */
	public void setArguments(List<Argument<?>> arguments) { this.arguments = arguments; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>If an explicit argument list was provided via {@link #setArguments}, maps over
	 * that list; otherwise falls back to the inherited {@link Scope} implementation.</p>
	 */
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

	/**
	 * Returns a {@link Consumer} that appends strings to this scope's code buffer.
	 *
	 * @return a consumer that accumulates code strings
	 */
	public Consumer<String> code() { return code::append; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Always returns {@code false}: explicit scopes cannot be inlined because
	 * the raw code string may contain side effects or multiline constructs that
	 * are not compatible with inline expansion.</p>
	 */
	@Override
	public boolean isInlineable() {
		if (!getChildren().isEmpty()) return false;
		if (!getMethods().isEmpty()) return false;
		if (!getVariables().isEmpty()) return false;
		return false; // TODO  Maybe it can be inlinable
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Writes the parent scope header and then either invokes the {@link #writer}
	 * callback (if set) or prints the accumulated code buffer.</p>
	 */
	@Override
	public void write(CodePrintWriter w) {
		super.write(w);

		if (writer != null) {
			writer.accept(w);
			return;
		}

		w.println(code.toString());
	}
}
