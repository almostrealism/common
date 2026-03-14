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

package io.almostrealism.relation;

/**
 * A marker interface for types that represent functional transformations.
 *
 * <p>{@link Function} represents a transformation from input type {@code IN}
 * to output type {@code OUT}. This is a semantic marker interface used to
 * indicate that a type represents a mathematical or computational function.</p>
 *
 * <h2>Purpose</h2>
 * <p>This interface serves as a tagging mechanism for types that have
 * function-like behavior. It is distinct from {@link java.util.function.Function}
 * and is used within the framework's type system.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link Factor} - A function from {@link Producer} to {@link Producer}</li>
 * </ul>
 *
 * @param <IN> the input type
 * @param <OUT> the output type
 *
 * @see Factor
 * @see java.util.function.Function
 *
 * @author Michael Murray
 */
@io.almostrealism.uml.Function
public interface Function<IN, OUT> {
}
