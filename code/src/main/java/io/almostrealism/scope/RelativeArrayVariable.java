/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.scope;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;

public class RelativeArrayVariable extends ArrayVariable<Double> {
	private ArrayVariable<Double> ref;
	private Expression offset;

	public RelativeArrayVariable(ArrayVariable<Double> ref, Expression offset) {
		super(null, (Class) ref.getType(), null, null);
		this.ref = ref;
		this.offset = offset;

		if (offset.isFP()) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public InstanceReference<?, ?> ref() {
		return ref.ref();
	}

	@Override
	public Expression<Double> getValueRelative(int index) {
		if (ref.getDelegate() != null) return ref.getValueRelative(index);

		TraversableExpression exp = TraversableExpression.traverse(ref.getProducer());

		if (exp != null && exp.isRelative()) {
			// Expression alt = exp.getValueAt(offset.add(new IntegerConstant(index)));
			return ref.getValueRelative(index);
		}

		if (exp != null) {
			return exp.getValueAt(offset.add(new IntegerConstant(index)));
		}

		return ref.reference(offset.add(new IntegerConstant(index)), false);
	}

	@Override
	public Expression<Double> referenceRelative(Expression<?> pos, KernelIndex idx) {
		if (ref.getDelegate() != null) return ref.referenceRelative(pos, idx);

		return ref.referenceRelative(pos.add(offset), idx);
	}
}
