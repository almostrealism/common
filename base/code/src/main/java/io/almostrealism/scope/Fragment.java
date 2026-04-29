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

package io.almostrealism.scope;

/**
 * Marker interface for objects that represent a partial or complete fragment of generated code.
 *
 * <p>Implementations of this interface are treated as individual, addressable code units
 * within a compiled scope. The interface carries no methods; its presence on a class or
 * expression signals to the code-generation pipeline that the object may be emitted as
 * a discrete code fragment.</p>
 */
public interface Fragment {
}
