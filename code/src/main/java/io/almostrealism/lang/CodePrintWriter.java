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

package io.almostrealism.lang;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.Statement;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Fragment;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.SystemUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A {@link CodePrintWriter} defines the contract for exporting {@link Scope} instances
 * and related code constructs to a specific target language.
 *
 * <p>This interface serves as the foundation of the code generation system, providing
 * methods to output various code constructs including scopes, methods, variables,
 * metrics, and comments. Each target language (e.g., C, OpenCL, Metal, Java) should
 * have its own implementation of this interface that handles language-specific
 * syntax and formatting.
 *
 * <p>Implementations typically manage:
 * <ul>
 *   <li>Scope nesting and indentation</li>
 *   <li>Variable declarations and assignments</li>
 *   <li>Method/function definitions and calls</li>
 *   <li>Language-specific statement terminators and syntax</li>
 *   <li>Optional metadata rendering for debugging purposes</li>
 * </ul>
 *
 * <p>The interface supports hierarchical scope management through
 * {@link #beginScope(String, OperationMetadata, Accessibility, List)} and
 * {@link #endScope()} methods, allowing nested code blocks to be properly
 * formatted in the target language.
 *
 * <p>Example usage:
 * <pre>{@code
 * CodePrintWriter writer = new CLanguagePrintWriter(printWriter);
 * writer.beginScope("myFunction", metadata, Accessibility.EXTERNAL, arguments);
 * writer.println(expressionAssignment);
 * writer.println(methodCall);
 * writer.endScope();
 * writer.flush();
 * }</pre>
 *
 * @see CodePrintWriterAdapter
 * @see LanguageOperations
 * @see Scope
 * @see Fragment
 *
 * @author Michael Murray
 */
public interface CodePrintWriter {
	/**
	 * Flag indicating whether operation metadata should be rendered as comments
	 * in the generated code. This is controlled by the {@code AR_HARDWARE_METADATA}
	 * system property or environment variable.
	 *
	 * <p>When enabled, metadata such as operation IDs, display names, descriptions,
	 * and signatures are written as comments to aid in debugging and understanding
	 * the generated code.
	 */
	boolean enableMetadata = SystemUtils.isEnabled("AR_HARDWARE_METADATA").orElse(false);

	/**
	 * Returns the {@link LanguageOperations} instance associated with this writer.
	 *
	 * <p>The {@link LanguageOperations} provides language-specific rendering utilities
	 * such as statement terminators, type mappings, and expression formatting that
	 * this writer uses when generating code.
	 *
	 * @return the language operations handler for the target language
	 */
	LanguageOperations getLanguage();

	/**
	 * Writes a raw string to the output.
	 *
	 * <p>This method directly outputs the provided string without any processing
	 * or language-specific formatting. It is primarily used for writing explicit
	 * scope contents or raw code snippets.
	 *
	 * <p><strong>Note:</strong> This method is deprecated because it bypasses
	 * the structured code generation provided by other methods. Prefer using
	 * {@link #println(Fragment)}, {@link #println(Scope)}, or other typed
	 * methods for better code generation hygiene.
	 *
	 * @param s the string to write
	 * @deprecated Use structured methods like {@link #println(Fragment)} or
	 *             {@link #println(Scope)} instead for proper code generation.
	 */
	@Deprecated
	void println(String s);

	/**
	 * Writes the specified {@link Metric} to the output.
	 *
	 * <p>Metrics contain variable mappings that are typically rendered as
	 * comments in the generated code for debugging and profiling purposes.
	 *
	 * @param m the metric to write
	 */
	void println(Metric m);

	/**
	 * Writes a {@link Fragment} to the output by dispatching to the appropriate
	 * typed method based on the fragment's runtime type.
	 *
	 * <p>This method provides a unified entry point for writing different code
	 * constructs. It determines the actual type of the fragment and delegates
	 * to the corresponding specialized method:
	 * <ul>
	 *   <li>{@link Scope} - delegates to {@link #println(Scope)}</li>
	 *   <li>{@link Method} - delegates to {@link #println(Method)}</li>
	 *   <li>{@link ExpressionAssignment} - delegates to {@link #println(ExpressionAssignment)}</li>
	 *   <li>{@link Statement} - renders the statement with appropriate terminator</li>
	 * </ul>
	 *
	 * @param s the fragment to write
	 * @throws IllegalArgumentException if the fragment type is not recognized
	 */
	default void println(Fragment s) {
		if (s instanceof Scope) {
			println((Scope<?>) s);
		} else if (s instanceof Method) {
			println((Method<?>) s);
		} else if (s instanceof ExpressionAssignment) {
			println((ExpressionAssignment) s);
		} else if (s instanceof Statement) {
			println(((Statement) s).getStatement(getLanguage()) + getLanguage().getStatementTerminator());
		} else {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Writes an {@link ExpressionAssignment} to the output, including both the
	 * variable declaration and its initial value.
	 *
	 * <p>This method renders a variable assignment statement in the target language.
	 * The implementation should handle:
	 * <ul>
	 *   <li>Variable type declaration (if required by the language)</li>
	 *   <li>Variable name</li>
	 *   <li>Assignment operator</li>
	 *   <li>Expression value</li>
	 *   <li>Statement terminator</li>
	 * </ul>
	 *
	 * @param v the expression assignment to write, containing the variable name
	 *          and the expression to assign
	 */
	void println(ExpressionAssignment<?> v);

	/**
	 * Writes a method call represented by the specified {@link Method} to the output.
	 *
	 * <p>This method renders a function or method invocation in the target language.
	 * The implementation typically uses the associated {@link LanguageOperations}
	 * to format the method call with appropriate syntax for arguments and invocation.
	 *
	 * @param m the method call to write, containing the method name and arguments
	 */
	void println(Method<?> m);

	/**
	 * Writes a complete {@link Scope} to the output.
	 *
	 * <p>This method renders a full code block or function definition, including:
	 * <ul>
	 *   <li>Scope header (function signature, block opening)</li>
	 *   <li>All contained statements and nested fragments</li>
	 *   <li>Scope footer (block closing)</li>
	 * </ul>
	 *
	 * <p>The scope's content is typically written by calling {@link Scope#write(CodePrintWriter)}
	 * which outputs all nested fragments.
	 *
	 * @param s the scope to write
	 */
	void println(Scope<?> s);

	/**
	 * Flushes any buffered output to the underlying output mechanism.
	 *
	 * <p>Implementations should ensure that all pending output is written
	 * to the destination (file, stream, etc.) when this method is called.
	 * This is particularly important for ensuring complete code files are
	 * written before compilation or execution.
	 */
	void flush();

	/**
	 * Begins a named scope with the specified metadata and accessibility.
	 *
	 * <p>This is a convenience method that delegates to
	 * {@link #beginScope(String, OperationMetadata, Accessibility, List, List)}
	 * with an empty parameter list.
	 *
	 * <p>Most implementations support {@code null} for the name parameter,
	 * which typically results in an anonymous code block.
	 *
	 * @param name      the name of the scope (function/method name), or {@code null}
	 *                  for anonymous blocks
	 * @param metadata  optional operation metadata to render as comments
	 * @param access    the accessibility level determining scope prefix
	 * @param arguments the list of array variable arguments for the scope signature
	 */
	default void beginScope(String name, OperationMetadata metadata, Accessibility access, List<ArrayVariable<?>> arguments) {
		beginScope(name, metadata, access, arguments, Collections.emptyList());
	}

	/**
	 * Begins a named scope with the specified metadata, accessibility, arguments,
	 * and additional parameters.
	 *
	 * <p>This method outputs the opening of a code block or function definition.
	 * The implementation should render:
	 * <ul>
	 *   <li>Access modifier or scope prefix (based on accessibility)</li>
	 *   <li>Scope name (if provided)</li>
	 *   <li>Argument list in appropriate syntax</li>
	 *   <li>Parameter list (if provided)</li>
	 *   <li>Block opening delimiter (e.g., "{" in C-like languages)</li>
	 * </ul>
	 *
	 * <p>Each call to {@code beginScope} must be balanced with a corresponding
	 * call to {@link #endScope()}.
	 *
	 * @param name       the name of the scope (function/method name), or {@code null}
	 *                   for anonymous blocks
	 * @param metadata   optional operation metadata to render as comments
	 * @param access     the accessibility level (EXTERNAL or INTERNAL)
	 * @param arguments  the list of array variable arguments for the scope signature
	 * @param parameters additional parameters for the scope signature
	 */
	void beginScope(String name, OperationMetadata metadata, Accessibility access, List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters);

	/**
	 * Ends the most recently opened scope.
	 *
	 * <p>This method outputs the closing delimiter for a scope (e.g., "}" in
	 * C-like languages) and performs any necessary cleanup such as restoring
	 * indentation level.
	 *
	 * <p>Each call to {@code endScope} must correspond to a previous call to
	 * {@link #beginScope(String, OperationMetadata, Accessibility, List)} or
	 * {@link #beginScope(String, OperationMetadata, Accessibility, List, List)}.
	 */
	void endScope();

	/**
	 * Renders operation metadata as comments in the generated code.
	 *
	 * <p>This is a convenience method that delegates to
	 * {@link #renderMetadata(OperationMetadata, int)} with an indent level of 0.
	 *
	 * <p>Metadata rendering is controlled by the {@link #enableMetadata} flag.
	 * When disabled, this method has no effect.
	 *
	 * @param metadata the operation metadata to render, may be {@code null}
	 */
	default void renderMetadata(OperationMetadata metadata) {
		renderMetadata(metadata, 0);
	}

	/**
	 * Renders operation metadata as comments in the generated code with
	 * hierarchical indentation.
	 *
	 * <p>When {@link #enableMetadata} is {@code true} and metadata is not {@code null},
	 * this method outputs the metadata as formatted comments including:
	 * <ul>
	 *   <li>Operation ID and display name</li>
	 *   <li>Short description</li>
	 *   <li>Long description (if present)</li>
	 *   <li>Signature (if present)</li>
	 *   <li>Child metadata (recursively rendered with increased indent)</li>
	 * </ul>
	 *
	 * <p>The indentation increases by 2 spaces per level, creating a visual
	 * hierarchy in the generated comments that reflects the operation structure.
	 *
	 * @param metadata the operation metadata to render, may be {@code null}
	 * @param indent   the indentation level (0 for top-level, increases for children)
	 * @throws IllegalArgumentException if metadata has a null or "null" display name
	 */
	default void renderMetadata(OperationMetadata metadata, int indent) {
		if (metadata != null && enableMetadata) {
			StringBuffer indentStr = new StringBuffer();
			IntStream.range(0, 2 * indent).forEach(i -> indentStr.append(" "));

			if (metadata.getDisplayName() == null || "null".equals(metadata.getDisplayName())) {
				throw new IllegalArgumentException();
			}

			comment(indentStr + " - [id=" + metadata.getId() + "] " +
					metadata.getDisplayName() + ": " + metadata.getShortDescription());

			if (metadata.getLongDescription() != null) {
				comment(indentStr + "     " + metadata.getLongDescription());
			}

			if (metadata.getSignature() != null) {
				comment(indentStr + "     " + metadata.getSignature());
			}

			if (metadata.getChildren() != null) {
				metadata.getChildren().forEach(meta -> renderMetadata(meta, indent + 1));
			}
		}
	}

	/**
	 * Writes a comment to the output in the target language's comment syntax.
	 *
	 * <p>Implementations should format the text as a single-line comment using
	 * the appropriate syntax for the target language (e.g., "// text" for C-like
	 * languages, "# text" for shell scripts).
	 *
	 * @param text the comment text to write
	 */
	void comment(String text);
}
