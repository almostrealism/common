/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Variable;

/**
 * @deprecated  In the process of abstracting the way in which {@link InstructionSet}s
 *              are created from {@link DynamicAcceleratedOperation}s, this type will
 *              inevitably become unusable because it does not allow for operations
 *              which are agnostic to the kind of {@link InstructionSet} ultimate used.
 */
@Deprecated
public interface ExplictBody<T> {
	String getBody(Variable<T, ?> outputVariable);
}
