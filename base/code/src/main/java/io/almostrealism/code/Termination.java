/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;

import java.util.Collections;
import java.util.List;

/**
 * A {@link Statement} that terminates execution of the enclosing kernel or function, rendering the
 * target language's return keyword (see {@link LanguageOperations#getReturnKeyword()}).
 *
 * <p>Used to model an early exit (such as an abort guard) without committing to any particular target
 * language during scope construction; the keyword is resolved only when the scope is rendered.</p>
 *
 * @see Statement
 * @see LanguageOperations#getReturnKeyword()
 */
public class Termination implements Statement<Termination> {
	@Override
	public String getStatement(LanguageOperations lang) {
		return lang.getReturnKeyword();
	}

	@Override
	public List<Variable<?, ?>> getDependencies() {
		return Collections.emptyList();
	}

	@Override
	public Termination simplify(KernelStructureContext context, int depth) {
		return this;
	}

	@Override
	public Termination replace(Expression target, Expression replacement) {
		return this;
	}
}
