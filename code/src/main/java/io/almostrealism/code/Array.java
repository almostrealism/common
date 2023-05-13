/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Delegated;
import io.almostrealism.scope.Variable;
import io.almostrealism.uml.Plural;

public interface Array<T, V extends Array<T, ?>> extends Plural<InstanceReference<T>>, Delegated<V> {
	@Override
	default InstanceReference<T> valueAt(int pos) {
		return get(new IntegerConstant(pos));
	}
	
	default InstanceReference<T> valueAt(Expression<?> exp) {
		return get(exp);
	}

	default String ref(int pos) {
		return valueAt(pos).getExpression();
	}

	InstanceReference<T> get(Expression<?> pos);

	Expression<Integer> length();
}
