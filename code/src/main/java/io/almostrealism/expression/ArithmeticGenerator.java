/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.ArithmeticIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public class ArithmeticGenerator<T extends Number> extends Product<T> {
	private final Expression<?> index;
	private final long scale;
	private final long granularity;
	private final long mod;

	public ArithmeticGenerator(Expression<?> index, long scale, long granularity, long mod) {
		super(List.of(new Quotient(List.of(index.imod(mod),
						ExpressionFeatures.getInstance().e(granularity))),
				ExpressionFeatures.getInstance().e(scale)));
		this.index = index;
		this.scale = scale;
		this.granularity = granularity;
		this.mod = mod;
	}

	public Expression<?> getIndex() { return index; }
	public long getScale() { return scale; }
	public long getGranularity() { return granularity; }
	public long getMod() { return mod; }

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (Objects.equals(index, getIndex())) {
			return new ArithmeticIndexSequence(getScale(), getGranularity(), getMod(), len);
		}

		return super.sequence(index, len, limit);
	}

	@Override
	public Expression<? extends Number> add(Expression<?> operand) {
		if (operand instanceof ArithmeticGenerator) {
			ArithmeticGenerator<?> ag = (ArithmeticGenerator<?>) operand;

			if (Objects.equals(getIndex(), ag.getIndex()) &&
					getGranularity() == ag.getGranularity() &&
					getMod() == ag.getMod()) {
				return new ArithmeticGenerator<>(getIndex(), getScale() + ag.getScale(), getGranularity(), getMod());
			}
		}

		return new Sum(List.of(this, operand));
	}

	@Override
	public Expression<? extends Number> multiply(Expression<?> operand) {
		OptionalLong d = operand.longValue();

		if (d.isPresent()) {
			return new ArithmeticGenerator<>(getIndex(), getScale() * d.getAsLong(), getGranularity(), getMod());
		}

		return new Product(List.of(this, operand));
	}

	@Override
	public Expression<? extends Number> divide(Expression<?> operand) {
		OptionalLong d = operand.longValue();

		if (d.isPresent()) {
			if (getScale() == 1) {
				return new ArithmeticGenerator<>(getIndex(), 1, d.getAsLong() * getGranularity(), getMod());
			} else if (getScale() % d.getAsLong() == 0) {
				return new ArithmeticGenerator<>(getIndex(), getScale() / d.getAsLong(), getGranularity(), getMod());
			}
		}

		if (getScale() == 1) {
			return getIndex().imod(getMod()).divide(operand);
		}

		return new Quotient<>(List.of(this, operand));
	}

	@Override
	public Expression eq(Expression<?> operand) {
		// TODO Optimization
		return super.eq(operand);
	}
}
