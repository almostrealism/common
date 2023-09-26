/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.stream.IntStream;

/**
 * A {@link CodePrintWriter} is implemented for each language that a {@link Scope} may be
 * exported to.
 */
public interface CodePrintWriter {
	boolean enableMetadata = SystemUtils.isEnabled("AR_HARDWARE_METADATA").orElse(false);

	/**
	 * This is used to write explicit scopes, but should be discouraged.
	 */
	@Deprecated
	void println(String s);

	/**
	 * Write the specified {@link Metric}.
	 */
	void println(Metric m);

	default void println(Statement s) {
		if (s instanceof Method) {
			println((Method<?>) s);
		} else if (s instanceof Variable) {
			println((Variable<?, ?>) s);
		} else {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Write the specified {@link Variable} (name of the variable and the data).
	 * This method should assume that the variable is to be created.
	 *
	 * @param v  Variable to print.
	 */
	void println(Variable<?, ?> v);

	/**
	 * Write a call to the function represented by the specified {@link Method}.
	 *
	 * @param m  Method call to print.
	 */
	void println(Method<?> m);

	/**
	 * Write the {@link Scope}.
	 *
	 * @param s  Computation to print.
	 */
	void println(Scope<?> s);

	/**
	 * Flush the underlying output mechanism.
	 */
	void flush();

	/**
	 * Begin a named scope. Most {@link CodePrintWriter} implementations support
	 * null for the name.
	 */
	void beginScope(String name, OperationMetadata metadata, List<ArrayVariable<?>> arguments, Accessibility access);

	/**
	 * End a scope which was introduced with {@link #beginScope(String, OperationMetadata, List, Accessibility)}.
	 */
	void endScope();

	default void renderMetadata(OperationMetadata metadata) {
		renderMetadata(metadata, 0);
	}

	default void renderMetadata(OperationMetadata metadata, int indent) {
		if (metadata != null && enableMetadata) {
			StringBuffer indentStr = new StringBuffer();
			IntStream.range(0, 2 * indent).forEach(i -> indentStr.append(" "));

			if (metadata.getDisplayName() == null || "null".equals(metadata.getDisplayName())) {
				throw new IllegalArgumentException();
			}

			comment(indentStr + " - " + metadata.getDisplayName() + ": " + metadata.getShortDescription());

			if (metadata.getLongDescription() != null) {
				comment(indentStr + "     " + metadata.getLongDescription());
			}

			metadata.getChildren().forEach(meta -> renderMetadata(meta, indent + 1));
		}
	}

	void comment(String text);
}
