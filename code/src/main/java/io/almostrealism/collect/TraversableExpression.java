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

package io.almostrealism.collect;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;

public interface TraversableExpression<T> extends ExpressionFeatures {

	default Expression<T> getValue(PositionExpression pos) {
		return getValue(pos.toArray());
	}

	Expression<T> getValue(Expression... pos);

	Expression<T> getValueAt(Expression index);
}
