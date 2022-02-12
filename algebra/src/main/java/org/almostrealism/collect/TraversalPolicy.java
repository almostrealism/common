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

import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TraversalPolicy {
	private int dims[];

	public TraversalPolicy(int... dims) {
		this.dims = dims;
	}

	public int size(int depth) {
		if (depth == dims.length) return 1;
		return IntStream.range(depth, dims.length).map(i -> dims[i]).reduce((x, y) -> x * y).getAsInt();
	}

	public int length(int axis) {
		return dims[axis];
	}

	public int[] position(int index) {
		int pos[] = new int[getDimensions()];

		int remaining = index;
		for (int i = 0; i < pos.length; i++) {
			int s = size(i + 1);
			pos[i] = remaining / s;
			remaining = remaining - pos[i] * s;
		}

		return pos;
	}

	public TraversalPolicy appendDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = length(i);
		newDims[newDims.length - 1] = size;
		return new TraversalPolicy(newDims);
	}

	public int getTotalSize() { return size(0); }

	public int getDimensions() { return dims.length; }

	public Stream<int[]> stream() {
		return IntStream.range(0, getTotalSize()).mapToObj(this::position);
	}
}
