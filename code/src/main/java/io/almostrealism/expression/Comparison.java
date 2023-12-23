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

package io.almostrealism.expression;

import io.almostrealism.kernel.KernelSeriesProvider;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public abstract class Comparison extends Expression<Boolean> {
	public static boolean enableKernelSimplification = true;

	private int[] latestBooleanSeq;

	public Comparison(Expression<?> left, Expression<?> right) {
		super(Boolean.class, left, right);
	}

	protected abstract boolean compare(Number left, Number right);

	public Expression<?> getLeft() { return getChildren().get(0); }
	public Expression<?> getRight() { return getChildren().get(1); }

	@Override
	public int[] booleanSeq(int len) {
		if (latestBooleanSeq != null && latestBooleanSeq.length >= len) {
			return Arrays.copyOf(latestBooleanSeq, len);
		} else if (getLeft().isKernelValue() && getRight().isKernelValue()) {
			int seq[] = IntStream.range(0, len)
					.map(i -> compare(getLeft().kernelValue(i), getRight().kernelValue(i)) ? 1 : 0)
					.toArray();
			latestBooleanSeq = seq;
			return seq;
		}

		return super.booleanSeq(len);
	}

	@Override
	public Expression<Boolean> simplify(KernelSeriesProvider provider) {
		Expression<?> flat = super.simplify(provider);
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

		if (enableKernelSimplification && provider != null) {
			OptionalInt max = provider.getMaximumLength();

			if (max.isPresent() && left.isKernelValue() && right.isKernelValue()) {
				Number n[] = left.kernelSeq(max.getAsInt());
				Number m[] = right.kernelSeq(max.getAsInt());

				boolean miss = false;
				boolean match = false;

				for (int i = 0; i < n.length; i++) {
					if (compare(n[i].doubleValue(), m[i].doubleValue())) {
						match = true;
					} else {
						miss = true;
					}
				}

				if (!miss) {
					return new BooleanConstant(true);
				} else if (!match) {
					return new BooleanConstant(false);
				}
			}
		}

		return (Expression<Boolean>) flat;
	}
}
