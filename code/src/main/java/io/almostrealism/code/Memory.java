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

package io.almostrealism.code;

public interface Memory {
	MemoryProvider getProvider();

	default void set(double... values) {
		set(0, values);
	}

	default void set(int offset, double... values) {
		getProvider().setMem(this, offset, values, 0, values.length);
	}

	default double[] toArray(int length) {
		return toArray(0, length);
	}

	default double[] toArray(int offset, int length) {
		return getProvider().toArray(this, offset, length);
	}
}
