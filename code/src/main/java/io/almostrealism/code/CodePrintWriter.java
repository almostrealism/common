/*
 * Copyright 2018 Michael Murray
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

import java.util.List;

/**
 * A {@link CodePrintWriter} is implemented for each language that a {@link Scope} may be
 * exported to.
 */
public interface CodePrintWriter {
	/**
	 * This is used to write explicit scopes, but should be discouraged.
	 */
	@Deprecated
	void println(String s);

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
	void beginScope(String name, List<ArrayVariable<?>> arguments, Accessibility access);

	/**
	 * End a scope which was introduced with {@link #beginScope(String, List, Accessibility)}.
	 */
	void endScope();
}
