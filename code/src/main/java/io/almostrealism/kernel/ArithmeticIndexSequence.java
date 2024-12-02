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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;

import java.util.stream.LongStream;
import java.util.stream.Stream;

public class ArithmeticIndexSequence implements IndexSequence, ExpressionFeatures {
	public static boolean enableAutoExpression = true;

	private long offset;
	private long scale;
	private long granularity;
	private long mod;
	private long len;

	public ArithmeticIndexSequence(long scale, long granularity, long len) {
		this(0, scale, granularity, len, len);
	}

	public ArithmeticIndexSequence(long scale, long granularity, long mod, long len) {
		this(0, scale, granularity, mod, len);
	}

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
	public IndexSequence multiply(long operand) {
		return new ArithmeticIndexSequence(offset * operand, scale * operand, granularity, mod, len);
	}

	@Override
	public IndexSequence divide(long operand) {
		if (offset != 0 || granularity != 1 || scale != 1) {
			return IndexSequence.super.divide(operand);
		}

		return new ArithmeticIndexSequence(0, scale, granularity * operand, mod, len);
	}

	@Override
	public IndexSequence minus() {
		return new ArithmeticIndexSequence(-offset, -scale, granularity, mod, len);
	}

	@Override
	public IndexSequence mod(long m) {
		if (offset != 0 || scale != 1 || mod % m != 0) {
			return IndexSequence.super.mod(m);
		}

		return new ArithmeticIndexSequence(0, 1, granularity, granularity * m, len);
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
		return Math.toIntExact(granularity);
	}

	@Override
	public int getMod() {
		return Math.toIntExact(mod);
	}

	@Override
	public Expression getExpression(Expression index, boolean isInt) {
		if (!enableAutoExpression) return IndexSequence.super.getExpression(index, isInt);

		Expression pos = index.imod(mod).divide(e(granularity));
		Expression r = pos.multiply(e(Math.abs(scale)));
		if (scale < 0) r = r.minus();
		if (offset != 0) r = r.add(e(offset));

//		Expression exp = IndexSequence.super.getExpression(index, isInt);
//		if (!r.equals(exp)) {
//			IndexSequence.super.getExpression(index, isInt);
//		}

		return r;
	}

	@Override
	public Stream<Number> values() {
		if (offset != 0) return IndexSequence.super.values();

		return LongStream.range(0, mod).mapToObj(this::valueAt);
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

	@Override
	public int hashCode() {
		return (int) mod;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ArithmeticIndexSequence)) return false;

		ArithmeticIndexSequence other = (ArithmeticIndexSequence) obj;
		return offset == other.offset && scale == other.scale &&
				granularity == other.granularity && mod == other.mod && len == other.len;
	}
}
