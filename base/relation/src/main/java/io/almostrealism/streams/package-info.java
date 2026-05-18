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
 * Streaming evaluation support for the Producer/Evaluable computation model.
 *
 * <p>This package provides asynchronous, push-based evaluation over a downstream
 * {@link java.util.function.Consumer}, complementing the pull-based {@code Evaluable}
 * contract defined in {@code io.almostrealism.relation}:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.streams.StreamingEvaluable} — extends {@code Evaluable}
 *       with a downstream consumer that receives results as they are computed.</li>
 *   <li>{@link io.almostrealism.streams.StreamingEvaluableBase} — abstract base class
 *       managing the downstream consumer and providing a default implementation for
 *       subclasses.</li>
 *   <li>{@link io.almostrealism.streams.EvaluableStreamingAdapter} — adapts an existing
 *       synchronous or {@link java.util.concurrent.Executor}-backed {@code Evaluable}
 *       into a {@code StreamingEvaluable}.</li>
 * </ul>
 */
package io.almostrealism.streams;
