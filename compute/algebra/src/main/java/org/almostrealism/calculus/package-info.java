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
 * Calculus and differentiation utilities for the Almost Realism framework.
 *
 * <p>Provides infrastructure for automatic differentiation and gradient computation
 * over {@link io.almostrealism.relation.Producer} graphs. Differentiation is behaviour of
 * the producer being differentiated ({@link org.almostrealism.collect.CollectionProducer#delta}).
 * A caller names the axis of the Jacobian it will contract with a
 * {@link org.almostrealism.calculus.ReduceAxis}, so that the vector-Jacobian product (reverse
 * mode) and the Jacobian-vector product (forward mode) are each a reduced delta that the
 * computation produces without forming the Jacobian, and
 * {@link org.almostrealism.calculus.ReduceAxis#NONE} asks for the Jacobian itself. A
 * computation applies what it knows of its own derivative in
 * {@link org.almostrealism.collect.CollectionProducer#differentiate}, and
 * {@link org.almostrealism.calculus.InputStub} isolates an input when the chain rule is applied.
 * The matrix operations that delta implementations compose (for example
 * {@link org.almostrealism.algebra.MatrixFeatures#expandAndMultiply}) live on
 * {@link org.almostrealism.algebra.MatrixFeatures}.</p>
 */
package org.almostrealism.calculus;
