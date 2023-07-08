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

import io.almostrealism.collect.RelativeSupport;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Delegated;

public class RelativeArrayVariable extends ArrayVariable<Double> {
	private ArrayVariable<Double> ref;
	private Expression offset;

	public RelativeArrayVariable(ArrayVariable<Double> ref, Expression offset) {
		super(null, null, (Expression<Integer>) null);
		this.ref = ref;
		this.offset = offset;
	}

	@Override
	public Expression<Double> getValueRelative(int index) {
		boolean fallback = true;
		Expression<Double> value = null;

		if (ref.getProducer() instanceof TraversableExpression) {
			value = ((TraversableExpression) ref.getProducer()).getValueRelative(new IntegerConstant(index));
		} else if (ref.getProducer() instanceof Delegated &&
				((Delegated) ref.getProducer()).getDelegate() instanceof TraversableExpression) {
			value = ((TraversableExpression) ((Delegated) ref.getProducer()).getDelegate())
					.getValueRelative(new IntegerConstant(index));
		} else {
			fallback = false;
		}

		if (ignore(fallback && value == null)) return ref.getValueRelative(index);

		Expression idx = offset.add(new IntegerConstant(index));

		if (ref.getProducer() instanceof TraversableExpression
				&& !(ref.getProducer() instanceof RelativeSupport)
		) {
			value = ((TraversableExpression) ref.getProducer()).getValueAt(idx);
			if (value != null) return value;
		} else if (ref.getProducer() instanceof Delegated &&
				((Delegated) ref.getProducer()).getDelegate() instanceof TraversableExpression
				&& !(((Delegated) ref.getProducer()).getDelegate() instanceof RelativeSupport)
		) {
			value = ((TraversableExpression) ((Delegated) ref.getProducer()).getDelegate())
					.getValueAt(idx);
			if (value != null) return value;
		}

		return ref.getRaw(idx);
	}

	private boolean ignore(boolean nonTraversable) {
		if (ref.getDelegate() != null) return true;
		if (nonTraversable) return false;
//		return false;
		return (ref.getProducer() instanceof TraversableExpression ||
				ref.getProducer() instanceof Delegated &&
						((Delegated) ref.getProducer()).getDelegate() instanceof TraversableExpression);
	}
}
