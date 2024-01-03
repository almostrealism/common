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

import io.almostrealism.kernel.KernelStructureContext;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public abstract class Comparison extends BinaryExpression<Boolean> {
	public static boolean enableKernelSimplification = false;

	private int[] latestBooleanSeq;

	public Comparison(Expression<?> left, Expression<?> right) {
		super(Boolean.class, left, right);
	}

	protected abstract boolean compare(Number left, Number right);

	@Override
	public int[] booleanSeq(int len) {
		if (latestBooleanSeq != null && latestBooleanSeq.length >= len) {
			return processSeq(latestBooleanSeq, len);
		} else if (!getLeft().isKernelValue() || !getRight().isKernelValue()) {
			return super.booleanSeq(len);
		}

		int seq[] = checkSingle(getLeft(), getRight(), len);
		if (seq != null) return seq;
		seq = checkSingle(getRight(), getLeft(), len);
		if (seq != null) return seq;

		Number l[] = getLeft().kernelSeq(len);
		Number r[] = getRight().kernelSeq(len);
		seq = IntStream.range(0, len)
				.map(i -> compare(l[i], r[i]) ? 1 : 0)
				.toArray();
		latestBooleanSeq = seq;
		return seq;
	}

	protected int[] checkSingle(Expression left, Expression right, int len) {
		return null;
	}

	@Override
	public Number evaluate(Number... children) {
		return compare(children[0], children[1]) ? 1 : 0;
	}

	@Override
	protected Expression<Boolean> populate(Expression<?> oldExpression) {
		if (oldExpression instanceof Comparison)
			latestBooleanSeq = ((Comparison) oldExpression).latestBooleanSeq;
		return super.populate(oldExpression);
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

		if (enableKernelSimplification) {
			OptionalInt max = context.getKernelMaximum();

			if (max.isPresent() && left.isKernelValue() && right.isKernelValue()) {
				distribution.addEntry("comparisonSimplify", 1);

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
					distribution.addEntry("comparisonSimplifyTrue", 1);
					return new BooleanConstant(true);
				} else if (!match) {
					distribution.addEntry("comparisonSimplifyFalse", 1);
					return new BooleanConstant(false);
				}
			}
		}

		return (Expression<Boolean>) flat;
	}
}
