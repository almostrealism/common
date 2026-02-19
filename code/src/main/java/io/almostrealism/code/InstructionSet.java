/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.lifecycle.Destroyable;

/** The InstructionSet interface. */
public interface InstructionSet extends Destroyable {
	/** Performs the get operation. */
	default Execution get() {
		return get("function");
	}

	/** Performs the get operation. */
	default Execution get(String function) {
		return get(function, 0);
	}

	/** Performs the get operation. */
	Execution get(String function, int argCount);

	/** Performs the isDestroyed operation. */
	boolean isDestroyed();
}
