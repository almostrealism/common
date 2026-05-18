/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.relation.Producer;

/**
 * A {@link Computation} that is also a {@link Producer}, representing a computation that
 * produces typed output values rather than executing for side effects.
 *
 * <p>{@code ProducerComputation} is the base interface for all value-producing computations
 * in the Almost Realism framework. Implementations are expected to provide a {@link io.almostrealism.scope.Scope}
 * for code generation and to support the {@link io.almostrealism.code.ScopeLifecycle}
 * for argument preparation and scope compilation.</p>
 *
 * @param <T> the type of value produced by this computation
 *
 * @see Computation
 * @see io.almostrealism.relation.Producer
 * @see Operator
 */
public interface ProducerComputation<T> extends Computation<T>, Producer<T> {
}
