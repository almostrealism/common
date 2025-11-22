/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.lang;

import io.almostrealism.code.Accessibility;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.Stack;

/**
 * An abstract base implementation of {@link CodePrintWriter} that provides common
 * functionality for code generation to C-like languages.
 *
 * <p>This adapter class handles the boilerplate of scope management, comment rendering,
 * and basic output operations, allowing concrete implementations to focus on
 * language-specific details. It uses a stack-based approach to track nested scopes
 * and supports configurable syntax elements like scope prefixes and delimiters.
 *
 * <p>Key features:
 * <ul>
 *   <li>Stack-based scope name tracking for proper nesting</li>
 *   <li>Configurable scope prefixes for external/internal accessibility</li>
 *   <li>Configurable scope delimiters (suffix and close characters)</li>
 *   <li>Built-in comment rendering with C-style syntax ("//")</li>
 *   <li>Delegation to {@link LanguageOperations} for method rendering</li>
 * </ul>
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #println(io.almostrealism.code.ExpressionAssignment)} - variable assignment rendering</li>
 * </ul>
 *
 * <p>Example of creating a concrete implementation:
 * <pre>{@code
 * public class CLanguagePrintWriter extends CodePrintWriterAdapter {
 *     public CLanguagePrintWriter(PrintWriter p) {
 *         super(p, new CLanguageOperations());
 *         setScopePrefix("void");
 *     }
 *
 *     @Override
 *     public void println(ExpressionAssignment<?> v) {
 *         // Render variable assignment
 *     }
 * }
 * }</pre>
 *
 * @see CodePrintWriter
 * @see LanguageOperations
 * @see DefaultLanguageOperations
 *
 * @author Michael Murray
 */
public abstract class CodePrintWriterAdapter implements CodePrintWriter {
	/**
	 * The underlying print writer used for output.
	 * All generated code is ultimately written through this writer.
	 */
	protected PrintWriter p;

	/**
	 * The language operations instance providing language-specific
	 * rendering utilities such as method formatting and type mappings.
	 */
	protected LanguageOperations language;

	/**
	 * Suffix appended to scope names. Defaults to empty string.
	 * Can be used for language-specific name decorations.
	 */
	private String nameSuffix = "";

	/**
	 * Prefix for externally accessible scopes (e.g., "void", "public", "__kernel").
	 * Used when accessibility is {@link Accessibility#EXTERNAL}.
	 */
	private String scopePrefixExt;

	/**
	 * Prefix for internally accessible scopes (e.g., "static", "private").
	 * Used when accessibility is not {@link Accessibility#EXTERNAL}.
	 */
	private String scopePrefixInt;

	/**
	 * Suffix/delimiter that appears after the scope signature.
	 * Defaults to "{" for C-like block opening.
	 */
	private String scopeSuffix = "{";

	/**
	 * Closing delimiter for scopes.
	 * Defaults to "}" for C-like block closing.
	 */
	private String scopeClose = "}";

	/**
	 * Stack tracking the names of currently open scopes.
	 * Used to maintain proper nesting and for scope-aware operations.
	 */
	private final Stack<String> scopeName;

	/**
	 * Constructs a new CodePrintWriterAdapter with the specified output writer
	 * and language operations.
	 *
	 * @param p        the print writer to use for output; must not be {@code null}
	 * @param language the language operations handler providing language-specific
	 *                 rendering utilities; must not be {@code null}
	 */
	public CodePrintWriterAdapter(PrintWriter p, LanguageOperations language) {
		this.p = p;
		this.language = language;
		this.scopeName = new Stack<>();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the language operations handler for this writer
	 */
	@Override
	public LanguageOperations getLanguage() {
		return language;
	}

	/**
	 * Sets both the external and internal scope prefix to the same value.
	 *
	 * <p>This is a convenience method for languages where there is no
	 * distinction between external and internal scope prefixes.
	 *
	 * @param prefix the prefix to use for all scope declarations
	 * @see #setExternalScopePrefix(String)
	 * @see #setInternalScopePrefix(String)
	 */
	protected void setScopePrefix(String prefix) {
		setExternalScopePrefix(prefix);
		setInternalScopePrefix(prefix);
	}

	/**
	 * Sets the prefix used for externally accessible scope declarations.
	 *
	 * <p>This prefix is prepended to scope declarations when the accessibility
	 * is {@link Accessibility#EXTERNAL}. For example, in OpenCL this might be
	 * "__kernel", while in C it might be "void" or "extern".
	 *
	 * @param prefix the prefix for external scopes, or {@code null} for no prefix
	 */
	protected void setExternalScopePrefix(String prefix) { this.scopePrefixExt = prefix; }

	/**
	 * Sets the prefix used for internally accessible scope declarations.
	 *
	 * <p>This prefix is prepended to scope declarations when the accessibility
	 * is not {@link Accessibility#EXTERNAL}. For example, in C this might be
	 * "static" for file-local functions.
	 *
	 * @param prefix the prefix for internal scopes, or {@code null} for no prefix
	 */
	protected void setInternalScopePrefix(String prefix) { this.scopePrefixInt = prefix; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation outputs each variable key from the metric as a comment.
	 *
	 * @param m the metric to write
	 */
	@Override
	public void println(Metric m) {
		m.getVariables().keySet().forEach(this::comment);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation uses C-style comment syntax ("// text").
	 *
	 * @param text the comment text to write
	 */
	@Override
	public void comment(String text) {
		println("// " + text);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation directly delegates to the underlying {@link PrintWriter}.
	 *
	 * @param s the string to write
	 * @deprecated Use structured methods instead
	 */
	@Override
	@Deprecated
	public void println(String s) { p.println(s); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation delegates to {@link LanguageOperations#renderMethod(Method)}
	 * to obtain the language-specific method call representation.
	 *
	 * @param method the method call to write
	 */
	@Override
	public void println(Method<?> method) {
		p.println(language.renderMethod(method));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation:
	 * <ol>
	 *   <li>Calls {@link #beginScope} with the scope's name and arguments</li>
	 *   <li>Invokes {@link Scope#write(CodePrintWriter)} to output the scope contents</li>
	 *   <li>Calls {@link #endScope} to close the scope</li>
	 * </ol>
	 *
	 * @param s the scope to write
	 */
	@Override
	public void println(Scope<?> s) {
		beginScope(s.getName(), null, Accessibility.EXTERNAL, s.getArgumentVariables());
		s.write(this);
		endScope();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation is a no-op. Subclasses that use buffered output
	 * should override this method to flush their buffers.
	 */
	@Override
	public void flush() { }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation builds a scope declaration line consisting of:
	 * <ol>
	 *   <li>Scope prefix (based on accessibility)</li>
	 *   <li>Scope name (with optional name suffix)</li>
	 *   <li>Argument list in parentheses</li>
	 *   <li>Parameter list (if provided)</li>
	 *   <li>Scope suffix (e.g., "{")</li>
	 * </ol>
	 *
	 * <p>The scope name is pushed onto an internal stack to track nesting.
	 * Arguments and parameters are rendered using {@link DefaultLanguageOperations}.
	 *
	 * @param name       the name of the scope (function/method name), or {@code null}
	 *                   for anonymous blocks
	 * @param metadata   optional operation metadata (not used in this implementation)
	 * @param access     the accessibility level determining which prefix to use
	 * @param arguments  the list of array variable arguments for the scope signature
	 * @param parameters additional parameters for the scope signature
	 */
	@Override
	public void beginScope(String name, OperationMetadata metadata, Accessibility access, List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters) {
		scopeName.push(name);

		StringBuilder buf = new StringBuilder();

		String scopePrefix = access == Accessibility.EXTERNAL ? scopePrefixExt : scopePrefixInt;

		if (name != null) {
			if (scopePrefix != null) { buf.append(scopePrefix); buf.append(" "); }

			buf.append(name);

			if (nameSuffix != null) {
				buf.append(nameSuffix);
			}

			buf.append("(");
			((DefaultLanguageOperations) language).renderArguments(arguments, buf::append, access);
			if (!arguments.isEmpty() && !parameters.isEmpty()) { buf.append(", "); }
			((DefaultLanguageOperations) language).renderParameters(parameters, buf::append, access);
			buf.append(")");
		}

		if (scopeSuffix != null) { buf.append(" "); buf.append(scopeSuffix); }

		p.println(buf.toString());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation pops the scope name from the internal stack,
	 * prints an empty line, and outputs the scope closing delimiter
	 * (e.g., "}").
	 */
	@Override
	public void endScope() {
		scopeName.pop();
		p.println();
		if (scopeClose != null) p.println(scopeClose);
	}
}
