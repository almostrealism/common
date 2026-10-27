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
 * The machine-learning engine's contribution to PDSL: the attention and rotary position
 * primitives, registered with every interpreter through
 * {@link org.almostrealism.ml.dsl.AttentionPrimitives}, a
 * {@link org.almostrealism.dsl.PdslExtension} discovered at runtime.
 *
 * <p>The language itself lives in {@link org.almostrealism.dsl}, in {@code ar-graph}.</p>
 */
package org.almostrealism.ml.dsl;
