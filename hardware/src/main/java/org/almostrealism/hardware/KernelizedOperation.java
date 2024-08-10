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

package org.almostrealism.hardware;

import io.almostrealism.relation.Countable;

public interface KernelizedOperation extends Countable {

	// TODO  It makes no sense for operation inputs to be divided
	// TODO  into an "output" and the remaining arguments

	/**
	 * {@link #kernelOperate(MemoryData...)} is preferred.
	 */
	void kernelOperate(MemoryBank output, MemoryData args[]);

	default void kernelOperate(MemoryData... args) {
		kernelOperate(null, args);
	}
}
