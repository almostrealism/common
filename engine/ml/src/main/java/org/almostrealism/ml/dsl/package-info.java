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
 * <p>The language reference — lexical structure, grammar, the meaning of every definition and
 * statement, name resolution, imports, the built-in catalog with each built-in's gradient
 * behavior, extension and known limits — is {@code docs/internals/pdsl-language.md} in the
 * repository.</p>
 *
 * <h2>Public Components</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.dsl.PdslLexer} — turns source text into tokens and owns the
 *       set of reserved words</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslToken} — a token: its type, text and source
 *       position</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslParser} — recursive-descent parser from tokens to a
 *       program</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslNode} — the syntax tree: the program, its imports and
 *       definitions, statements and expressions</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslParseException} — every lexing, parsing, import and
 *       interpretation error; carries only a message</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslInterpreter} — builds layers and models from a
 *       program and evaluates its config, data and state blocks</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslLoader} — the entry point: parses sources and
 *       classpath resources (resolving imports and caching parsed resources), and builds through
 *       interpreters configured by a primitive registrar</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslPrimitive} — a function a domain module registers
 *       with the interpreter</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslPrimitiveContext} — the per-call context handed to a
 *       primitive: argument normalisation and the multi-channel environment</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslChannelBank} — the whole-bank argument of a
 *       vectorized {@code for each channel} body</li>
 *   <li>{@link org.almostrealism.ml.dsl.PdslMultiChannelDispatcher} — builds the block behind
 *       {@code for each channel}, supplied by a domain module</li>
 * </ul>
 *
 * <p>The built-in function library ({@code PdslBuiltins}) and the feature mixin it and the
 * interpreter use ({@code PdslFeatures}) are package-private implementation details: programs
 * reach the built-ins by name, and domains add functions through {@code PdslPrimitive} rather
 * than by extending the library.</p>
 */
package org.almostrealism.ml.dsl;
