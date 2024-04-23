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

package io.almostrealism.expression;

import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public abstract class Comparison extends BinaryExpression<Boolean> {
	public Comparison(Expression<?> left, Expression<?> right) {
		super(Boolean.class, left, right);
	}

	protected abstract boolean compare(Number left, Number right);

	@Override
	public boolean isValue(IndexValues values) {
		return getLeft().isValue(values) && getRight().isValue(values);
	}

	@Override
	public Number value(IndexValues indexValues) {
		return compare(getLeft().value(indexValues), getRight().value(indexValues)) ? 1 : 0;
	}

	@Override
	public IndexSequence sequence(Index index, long len) {
		IndexValues values = IndexValues.of(index);
		if (!getLeft().isValue(values) || !getRight().isValue(values)) {
			return super.sequence(index, len);
		}

		if (index instanceof KernelIndex) {
			int seq[] = checkSingle(getLeft(), getRight(), Math.toIntExact(len));
			if (seq != null) return IndexSequence.of(seq);

			seq = checkSingle(getRight(), getLeft(), Math.toIntExact(len));
			if (seq != null) return IndexSequence.of(seq);
		}

		IndexSequence l = getLeft().sequence(index, len);
		IndexSequence r = getRight().sequence(index, len);
		return compare(l, r, Math.toIntExact(len));
	}

	protected IndexSequence compare(IndexSequence left, IndexSequence right, int len) {
		return IndexSequence.of(Integer.class, IntStream.range(0, len)
				.mapToObj(i -> compare(left.valueAt(i), right.valueAt(i)) ? Integer.valueOf(1) : Integer.valueOf(0))
				.toArray(Number[]::new));
	}

	protected int[] checkSingle(Expression left, Expression right, int len) {
		return null;
	}

	@Override
	public Number evaluate(Number... children) {
		return compare(children[0], children[1]) ? 1 : 0;
	}

	@Override
	public Expression<Boolean> simplify(KernelStructureContext context) {
		Expression<?> flat = super.simplify(context);
		if (!Objects.equals(flat.getClass(), getClass())) return (Expression<Boolean>) flat;

		Expression<?> left = flat.getChildren().get(0);
		Expression<?> right = flat.getChildren().get(1);

		OptionalInt li = left.intValue();
		OptionalInt ri = right.intValue();
		if (li.isPresent() && ri.isPresent())
			return new BooleanConstant(compare(li.getAsInt(), ri.getAsInt()));

		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();
		if (ld.isPresent() && rd.isPresent())
			return new BooleanConstant(compare(ld.getAsDouble(), rd.getAsDouble()));

		return (Expression<Boolean>) flat;
	}
}
