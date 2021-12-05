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

package org.almostrealism.hardware.external;

import io.almostrealism.code.InstructionSet;

import java.util.function.Consumer;

public class ExternalInstructionSet implements InstructionSet {
	private String executable;

	public ExternalInstructionSet(String executable) {
		this.executable = executable;
	}

	@Override
	public Consumer<Object[]> get(String function, int argCount) {
		return args -> {
			// TODO  Export argument data to a collection of binary files
			//       with names matching the argument names
			// TODO  Start a Process for the executable
			// TODO  Wait for it to complete
			// TODO  Read the binary files back from the same location as they were written
		};
	}

	@Override
	public boolean isDestroyed() { return false; }

	@Override
	public void destroy() { }
}
