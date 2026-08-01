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
 * Language-specific code generation operations and scope encoding.
 *
 * <p>This package provides the abstraction layer between the platform-independent expression
 * model and the concrete syntax of each target language (C, OpenCL, Metal, etc.).</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.lang.LanguageOperations} — the interface that all target
 *       languages must implement to participate in code generation; defines operations such
 *       as variable declaration, assignment, arithmetic functions, and kernel index access</li>
 *   <li>{@link io.almostrealism.lang.DefaultLanguageOperations} — a base implementation for
 *       C-family languages that provides shared formatting logic</li>
 *   <li>{@link io.almostrealism.lang.LanguageOperationsStub} — a minimal stub for analysis
 *       passes that do not require actual code generation</li>
 *   <li>{@link io.almostrealism.lang.ScopeEncoder} — renders a complete
 *       {@link io.almostrealism.scope.Scope} to a string of generated code</li>
 * </ul>
 */
package io.almostrealism.lang;
