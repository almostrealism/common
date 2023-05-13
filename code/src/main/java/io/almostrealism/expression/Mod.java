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

import java.util.List;

public class Mod extends Expression<Double> {
	private boolean fp;

	public Mod(Expression<Double> a, Expression<Double> b) {
		this(a, b, true);
	}

	public Mod(Expression<Double> a, Expression<Double> b, boolean fp) {
		super(Double.class,
				fp ? "fmod(" + a.getExpression() + ", " + b.getExpression() + ")" :
						"(" + a.getExpression() + ") % (" + b.getExpression() + ")",
				a, b);
		this.fp = fp;
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Mod((Expression<Double>) children.get(0), (Expression<Double>) children.get(1), fp);
	}
}
