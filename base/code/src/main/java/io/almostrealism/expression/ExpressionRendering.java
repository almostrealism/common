/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Textual rendering and search for {@link Expression}.
 *
 * <p>Defines the {@link #getExpression(LanguageOperations) code-rendering contract} that every
 * concrete expression implements, together with the derived views built on top of it: a
 * simplified rendering, a parenthesised rendering, a debug summary, and a text search over the
 * subtree. These are co-located on this mixin to keep {@link Expression} focused on structure,
 * evaluation, and simplification. {@link Expression} implements this interface.</p>
 *
 * @param <T> the numeric result type of the implementing {@link Expression}
 */
public interface ExpressionRendering<T> {

	/**
	 * Returns this rendering mixin as the {@link Expression} it is mixed into.
	 *
	 * @return this, viewed as an {@link Expression}
	 */
	private Expression<T> self() {
		return (Expression<T>) this;
	}

	/**
	 * Returns the simplified expression rendered as target language code.
	 *
	 * <p>This is a convenience method that first simplifies the expression and then
	 * renders it using the specified language operations.</p>
	 *
	 * @param lang the language operations for code generation
	 * @return the simplified expression as code, or {@code null} if simplification fails
	 */
	public default String getSimpleExpression(LanguageOperations lang) {
		return Optional.ofNullable(self().getSimplified())
				.map(e -> e.getExpression(lang)).orElse(null);
	}

	/**
	 * Renders this expression as code in the target language.
	 *
	 * <p>This is the primary code generation method. Each expression subclass must
	 * implement this to produce the appropriate syntax for the target language.
	 * The {@link LanguageOperations} parameter provides language-specific operators,
	 * type names, and syntax rules.</p>
	 *
	 * @param lang the language operations defining the target language syntax
	 * @return the expression rendered as code
	 */
	String getExpression(LanguageOperations lang);

	/**
	 * Returns this expression rendered as code wrapped in parentheses.
	 *
	 * <p>This is useful for ensuring correct operator precedence when the expression
	 * is used as a subexpression.</p>
	 *
	 * @param lang the language operations for code generation
	 * @return the expression as code wrapped in parentheses
	 */
	public default String getWrappedExpression(LanguageOperations lang) {
		return "(" + self().getExpression(lang) + ")";
	}

	/**
	 * Returns a brief summary of this expression for debugging and logging.
	 *
	 * <p>For shallow expressions (depth less than 10), returns the full expression.
	 * For deeper expressions, returns a summary showing the class name, type, and depth
	 * to avoid generating excessively long strings.</p>
	 *
	 * @return a human-readable summary of this expression
	 */
	public default String getExpressionSummary() {
		if (self().treeDepth() < 10) return self().getExpression(Expression.defaultLanguage());
		return getClass().getSimpleName() + "<" +
					self().getType().getSimpleName() +
				">[depth=" + self().treeDepth() + "]";
	}

	/**
	 * Searches for subexpressions whose code representation contains the specified text.
	 *
	 * <p>This method performs a depth-first search, returning the deepest (most specific)
	 * subexpressions that contain the text. If no children contain the text but this
	 * expression does, returns this expression.</p>
	 *
	 * @param text the text to search for in the expression code
	 * @return a list of expressions whose code contains the text
	 */
	public default List<Expression> find(String text) {
		List<Expression> found = new ArrayList<>();
		for (Expression e : self().getChildren()) {
			found.addAll(e.find(text));
		}

		if (found.isEmpty() && self().getExpression(new LanguageOperationsStub()).contains(text)) {
			found.add(self());
		}

		return found;
	}

}
