/*
 * Copyright 2021 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.Collection;

// TODO  Should extend Repeated
public class Loop extends OperationComputationAdapter<Void> {
	private final Computation atom;
	private final int iterations;

	public Loop(Computation<Void> atom, int iterations) {
		this.atom = atom;
		this.iterations = iterations;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		atom.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		atom.prepareScope(manager);
	}

	@Override
	public int getCount() {
		return atom instanceof Countable ? ((Countable) atom).getCount() : 1;
	}

	@Override
	public Scope<Void> getScope() {
		Scope<Void> atomScope = atom.getScope();
		atomScope.convertArgumentsToRequiredScopes();

		HybridScope<Void> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "Loop x" + iterations));
		scope.getRequiredScopes().add(atomScope);

		String i = getVariablePrefix() + "_i";

		scope.setSource((s, lang) -> {
			StringBuilder code = new StringBuilder();
			code.append("for (int " + i + " = 0; " + i + " < " + iterations + "; " + i + "++) {\n");
			code.append("    " + lang.renderMethod(s.getRequiredScopes().get(0).call()) + "\n");
			code.append("}\n");
			return code.toString();
		});

		scope.getExplicit().setWriter(w -> {
			w.println("for (int " + i + " = 0; " + i + " < " + iterations + "; " + i + "++) {\n");
			atomScope.write(w);
			w.println("}\n");
		});

		return scope;
	}
}
