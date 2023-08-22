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
import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Compactable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.almostrealism.hardware.cl.OpenCLPrintWriter;
import org.almostrealism.hardware.OperationComputationAdapter;

// TODO  Should extend Repeated
public class Loop extends OperationComputationAdapter<Void> {
	public static final boolean enableCompaction = true;

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
		scope.code().accept("for (int " + i + " = 0; " + i + " < " + iterations +"; " + i + "++) {\n");
		scope.code().accept("    " + Hardware.getLocalHardware().getComputeContext().getLanguage().renderMethod(atomScope.call()) + "\n");
		scope.code().accept("}\n");
		return scope;
	}

	@Override
	public synchronized void compact() {
		super.compact();
		if (enableCompaction && atom instanceof Compactable) {
			((Compactable) atom).compact();
		}
	}
}
