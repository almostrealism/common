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

/**
 * Scope tree and variable management for the code generation model.
 *
 * <p>A {@link io.almostrealism.scope.Scope} is the hierarchical container that holds
 * variables, arguments, and child scopes for a single compiled function or block.
 * The scope tree is built during {@code prepareScope} and then rendered to native code
 * via {@link io.almostrealism.lang.ScopeEncoder}.</p>
 *
 * <p>Key types in this package:</p>
 * <ul>
 *   <li>{@link io.almostrealism.scope.Scope} — the root container for code generation</li>
 *   <li>{@link io.almostrealism.scope.Variable} — a named, typed storage location</li>
 *   <li>{@link io.almostrealism.scope.ArrayVariable} — a variable referencing an array
 *       with an optional delegate, offset, and size expression</li>
 *   <li>{@link io.almostrealism.scope.Argument} — an input argument to a scope function</li>
 *   <li>{@link io.almostrealism.scope.Method} — a method call statement within a scope</li>
 *   <li>{@link io.almostrealism.scope.Repeated} — a loop construct that repeats a child scope</li>
 *   <li>{@link io.almostrealism.scope.ScopeSettings} — global settings for simplification
 *       and code generation behavior</li>
 * </ul>
 */
package io.almostrealism.scope;
