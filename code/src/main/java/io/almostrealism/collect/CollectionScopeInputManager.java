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

import java.util.function.Supplier;

/**
 * A specialized {@link DefaultScopeInputManager} that creates {@link CollectionVariable}
 * instances for scope inputs. This manager extends the default scope input handling to
 * support collection-based variables with shape information.
 *
 * <p>When inputs are registered with this manager, it uses the
 * {@link CollectionVariable#create(String, Supplier)} factory method to create
 * appropriate variable instances. This allows inputs that implement {@link Shape}
 * to be represented as {@code CollectionVariable} instances with proper shape tracking.</p>
 *
 * @see DefaultScopeInputManager
 * @see CollectionVariable
 */
public class CollectionScopeInputManager extends DefaultScopeInputManager {

	/**
	 * Constructs a new {@code CollectionScopeInputManager} with the specified language operations.
	 * The manager is configured with a variable factory that creates {@link CollectionVariable}
	 * instances using auto-generated argument names.
	 *
	 * @param lang the {@link LanguageOperations} providing language-specific functionality
	 */
	public CollectionScopeInputManager(LanguageOperations lang) {
		super(lang);
		setVariableFactory((p, input) -> CollectionVariable.create(p.getArgumentName(counter++), (Supplier) input));
	}

	/**
	 * Factory method to create a new {@code CollectionScopeInputManager} instance.
	 *
	 * @param lang the {@link LanguageOperations} providing language-specific functionality
	 * @return a new {@code CollectionScopeInputManager} instance
	 */
	public static CollectionScopeInputManager getInstance(LanguageOperations lang) {
		return new CollectionScopeInputManager(lang);
	}
}
