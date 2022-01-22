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

package org.almostrealism.generated;

import io.almostrealism.code.Computation;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeInstructionSet;

public abstract class BaseGeneratedOperation<T extends MemoryData> implements NativeInstructionSet {
	private Computation<T> computation; // TODO  Remove

	public BaseGeneratedOperation(Computation<T> computation) {
		this.computation = computation;
	}
}
