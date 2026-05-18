/*
 * Copyright 2026 Michael Murray
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

/**
 * Producer DSL (PDSL) — a domain-specific language for declaratively
 * describing AlmostRealism computation graphs.
 *
 * <p>PDSL allows models and layers to be expressed as text scripts that are
 * parsed into an AST ({@link org.almostrealism.ml.dsl.PdslNode}), interpreted
 * by {@link org.almostrealism.ml.dsl.PdslInterpreter} into
 * {@link org.almostrealism.model.Block} and {@link org.almostrealism.model.Model}
 * objects, and then compiled for GPU/CPU execution via the AR hardware framework.</p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.dsl.PdslParser} — tokenizes and parses PDSL source text</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslNode} — AST node hierarchy</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslInterpreter} — walks the AST and builds AR blocks</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslParseException} — parse-time error reporting</li>
 * </ul>
 */
package org.almostrealism.ml.dsl;
