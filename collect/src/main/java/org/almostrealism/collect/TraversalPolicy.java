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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;

import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TraversalPolicy implements Traversable<TraversalPolicy> {
	private int dims[];
	private int traversalAxis;

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

	public int index(int... pos) {
		int index = 0;
		for (int i = 0; i < pos.length; i++) {
			index += pos[i] * size(i + 1);
		}
		return index;
	}

	public Expression index(Expression... pos) {
		Expression index = new Expression(Double.class, "0");

		for (int i = 0; i < pos.length; i++) {
			Expression s = new Expression<>(Double.class, String.valueOf(size(i + 1)));
			index = new Sum(index, new Product(pos[i], s));
		}

		return index;
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

	public Expression[] position(Expression index) {
		Expression pos[] = new Expression[getDimensions()];

		Expression remaining = index;
		for (int i = 0; i < pos.length; i++) {
			Expression s = new Expression<>(Double.class, String.valueOf(size(i + 1)));
			pos[i] = new Quotient(remaining, s);
			remaining = new Sum(remaining, new Minus(new Product(pos[i], s)));
		}

		return pos;
	}

	public Expression subset(TraversalPolicy shape, Expression index, int... loc) {
		Expression pos[] = shape.position(index);

		for (int i = 0; i < loc.length; i++) {
			Expression l = new Expression<>(Double.class, String.valueOf(loc[i]));
			pos[i] = new Sum(pos[i], l);
		}

		return index(pos);
	}

	@Override
	public TraversalPolicy traverse(int axis) {
		TraversalPolicy p = new TraversalPolicy(dims);
		p.traversalAxis = axis;
		return p;
	}

	public TraversalPolicy traverseEach() {
		return traverse(getDimensions());
	}

	public TraversalPolicy prependDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		newDims[0] = size;
		for (int i = 0; i < getDimensions(); i++) newDims[i + 1] = length(i);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis + 1;
		return p;
	}

	public TraversalPolicy appendDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = length(i);
		newDims[newDims.length - 1] = size;

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy subset(int depth) {
		int newDims[] = new int[getDimensions() - depth];
		for (int i = 0; i < newDims.length; i++) newDims[i] = length(i + depth);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis > depth ? traversalAxis - depth : 0;
		return p;
	}

	public int getTraversalAxis() { return traversalAxis; }

	public int getSize() { return size(traversalAxis); }

	public int getTotalSize() { return size(0); }

	public int getCount() { return getTotalSize() / getSize(); }

	public int getDimensions() { return dims.length; }

	public Stream<int[]> stream() {
		return IntStream.range(0, getTotalSize()).mapToObj(this::position);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("(");
		for (int i = 0; i < dims.length; i++) {
			sb.append(dims[i]);
			if (i < dims.length - 1) sb.append(", ");
		}
		sb.append(")");
		return sb.toString();
	}
}
