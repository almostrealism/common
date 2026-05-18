/*
 * Copyright 2025 Michael Murray
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
 * Top-level entry points for the Almost Realism framework.
 *
 * <p>This package provides the primary access interfaces for the framework,
 * including {@link org.almostrealism.Ops} (the singleton entry point) and
 * {@link org.almostrealism.CodeFeatures} (the master feature interface that
 * aggregates all computational capabilities).</p>
 *
 * <p>Most users will interact with the framework through these classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.Ops} - Singleton access to all operations</li>
 *   <li>{@link org.almostrealism.CodeFeatures} - Mixin interface for implementing classes</li>
 * </ul>
 *
 * @see org.almostrealism.Ops
 * @see org.almostrealism.CodeFeatures
 */
package org.almostrealism;
