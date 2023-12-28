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

import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

public class Quotient<T extends Number> extends NAryExpression<T> {
	public Quotient(Expression<Double>... values) {
		super((Class<T>) type(values), "/", values);
	}

	@Override
	public KernelSeries kernelSeries() {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		KernelSeries numerator = getChildren().get(0).kernelSeries();
		OptionalInt denominator = getChildren().get(1).intValue();

		if (denominator.isPresent()) {
			return numerator.scale(denominator.getAsInt());
		}

		return  KernelSeries.infinite();
	}

	@Override
	public OptionalInt upperBound() {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		OptionalInt l = getChildren().get(0).upperBound();
		OptionalInt r = getChildren().get(1).upperBound();
		if (l.isPresent() && r.isPresent()) {
			return OptionalInt.of((int) Math.ceil(l.getAsInt() / (double) r.getAsInt()));
		}

		return OptionalInt.empty();
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		Number numerator = getChildren().get(0).kernelValue(kernelIndex);
		Number denominator = getChildren().get(1).kernelValue(kernelIndex);

		if (numerator instanceof Integer && denominator instanceof Integer) {
			return ((Integer) numerator) / ((Integer) denominator);
		} else {
			return numerator.doubleValue() / denominator.doubleValue();
		}
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return new Quotient(children.toArray(new Expression[0]));
	}

	@Override
	public Expression simplify(KernelStructureContext context) {
		Expression<?> flat = super.simplify(context);
		if (!enableSimplification || !(flat instanceof Quotient)) return flat;

		List<Expression<?>> children = flat.getChildren().subList(1, flat.getChildren().size()).stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 1.0)
				.collect(Collectors.toList());
		children.add(0, flat.getChildren().get(0));

		if (children.isEmpty()) return getChildren().iterator().next(); // TODO  This is wrong
		if (children.size() == 1) return children.get(0);

		if (children.get(0).intValue().isPresent()) {
			int numerator = children.get(0).intValue().getAsInt();
			if (numerator == 0) return new IntegerConstant(0).toInt();

			int i;
			i: for (i = 1; i < children.size(); i++) {
				if (children.get(i).intValue().isPresent()) {
					numerator = numerator / children.get(i).intValue().getAsInt();
				} else {
					break i;
				}
			}

			if (i == children.size()) return new IntegerConstant(numerator).toInt();
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.add(new IntegerConstant(numerator).toInt());
			newChildren.addAll(children.subList(i, children.size()));
			children = newChildren;
		} else if (children.get(0).doubleValue().isPresent()) {
			double numerator = children.get(0).doubleValue().getAsDouble();
			if (numerator == 0) return new DoubleConstant(0.0);

			int i;
			i: for (i = 1; i < children.size(); i++) {
				if (children.get(i).doubleValue().isPresent()) {
					numerator = numerator / children.get(i).doubleValue().getAsDouble();
				} else {
					break i;
				}
			}

			if (i == children.size()) return new DoubleConstant(numerator);
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.add(new DoubleConstant(numerator));
			newChildren.addAll(children.subList(i, children.size()));
			children = newChildren;
		}

		return generate(children).populate(this);
	}
}
