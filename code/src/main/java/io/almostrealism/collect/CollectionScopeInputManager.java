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

package io.almostrealism.collect;

import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.relation.Producer;

import java.util.function.Supplier;

public class CollectionScopeInputManager extends DefaultScopeInputManager {
	public CollectionScopeInputManager(LanguageOperations lang) {
		super(lang);
		setVariableFactory((p, input) -> CollectionVariable.create(p, p.getArgumentName(counter++), (Supplier) input));
	}

	public static CollectionScopeInputManager getInstance(LanguageOperations lang) {
		return new CollectionScopeInputManager(lang);
	}
}
