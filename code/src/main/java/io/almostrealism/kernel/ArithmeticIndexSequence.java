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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;

import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

public class ArithmeticIndexSequence implements IndexSequence {
	private long offset;
	private long scale;
	private long granularity;
	private long mod;
	private long len;

	public ArithmeticIndexSequence(long offset, long scale, long granularity, long mod, long len) {
		this.offset = offset;
		this.scale = scale;
		this.granularity = granularity;
		this.mod = mod;
		this.len = len;
	}

	@Override
	public Number valueAt(long pos) {
		pos = (pos % mod) / granularity;
		return offset + scale * pos;
	}

	@Override
	public IndexSequence map(UnaryOperator<Number> op) {
		throw new UnsupportedOperationException();
	}

	@Override
	public IndexSequence mapInt(IntUnaryOperator op) {
		throw new UnsupportedOperationException();
	}

	@Override
	public IndexSequence mapDouble(DoubleUnaryOperator op) {
		throw new UnsupportedOperationException();
	}

	@Override
	public IndexSequence mod(int m) {
		throw new UnsupportedOperationException();
	}

	@Override
	public IndexSequence eq(IndexSequence other) {
		throw new UnsupportedOperationException();
	}

	@Override
	public IndexSequence subset(long len) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isConstant() {
		return lengthLong() == 1;
	}

	@Override
	public int getGranularity() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getMod() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Expression<? extends Number> getExpression(Index index) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long lengthLong() {
		return len;
	}

	@Override
	public Number[] toArray() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Class<? extends Number> getType() {
		return Integer.class;
	}
}
