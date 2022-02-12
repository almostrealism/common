/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.collect;

import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.stream.IntStream;

public class Position extends MemoryDataAdapter {
	private int axies;

	public Position(int... pos) {
		init();
		setMem(IntStream.of(pos).mapToDouble(i -> i).toArray());
	}

	@Override
	public int getMemLength() { return axies; }
}
