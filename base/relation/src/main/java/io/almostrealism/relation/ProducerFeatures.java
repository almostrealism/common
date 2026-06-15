/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.relation;

/**
 * A mixin interface reserved for utility methods that operate on {@link Producer}s.
 *
 * <p>This follows the "features" pattern common in the framework, where interfaces
 * provide default implementations of utility methods. {@link ProducerFeatures}
 * currently declares no methods and has no implementors; it exists as an extension
 * point for producer-level utilities that belong in the relation module rather
 * than in a higher layer.</p>
 *
 * @see Producer
 *
 * @author Michael Murray
 */
public interface ProducerFeatures {

}
