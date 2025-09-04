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

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;

public interface ComputeContext<MEM> {
	DataContext<MEM> getDataContext();

	LanguageOperations getLanguage();

	/**
	 * Deliver the specified {@link Scope} to the compute engine represented by this
	 * {@link ComputeContext}. The resulting {@link InstructionSet} can be used to
	 * obtain the actual compute functionality, with each entrypoint in the {@link Scope
	 * represented as a unique {@link java.util.function.Consumer}.
	 */
	InstructionSet deliver(Scope scope);

	void runLater(Runnable runnable);

	boolean isCPU();

	default boolean isProfiling() {
		return false;
	}

	void destroy();
}
