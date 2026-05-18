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
 * Algebra-specific producer computation implementations.
 *
 * <p>Contains computation nodes for algebraic operations such as weighted sums,
 * ranked choice, matrix computations, and conditional selection. These implement
 * {@link io.almostrealism.relation.Producer} and generate native kernel code for
 * hardware-accelerated execution.</p>
 */
package org.almostrealism.algebra.computations;
