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

/**
 * A {@link MemoryBank} tracks a section of RAM that is used to
 * store a collection of {@link MemoryData}s in a single
 * {@link io.almostrealism.code.Memory} instance.
 *
 * @author  Michael Murray
 */
public interface MemoryBank<T extends MemoryData> extends MemoryData, Countable {
	T get(int index);
	void set(int index, T value);
}
