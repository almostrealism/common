/*
 * Copyright 2020 Michael Murray
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

/**
 * The provider passed to computations during scope compilation.
 *
 * <p>{@code ScopeInputManager} supplies {@link io.almostrealism.scope.ArrayVariable}
 * instances for input producers while a computation's scope is being prepared. Scope
 * generation is language-independent: the target language is consulted only later, when
 * the scope is rendered by the code print writer.</p>
 *
 * @see ArgumentProvider
 * @see ScopeLifecycle
 */
public interface ScopeInputManager extends ArgumentProvider {
}
