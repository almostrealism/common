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

package org.almostrealism.hardware.metal;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

public abstract class MTLObject implements ConsoleFeatures {
	private long nativePointer;

	public MTLObject(long nativePointer) {
		this.nativePointer = nativePointer;
	}

	public long getNativePointer() {
		return nativePointer;
	}

	@Override
	public Console console() {
		return Hardware.console;
	}
}
