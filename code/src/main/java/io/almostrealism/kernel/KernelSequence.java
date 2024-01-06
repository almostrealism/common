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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IndexValues;
import io.almostrealism.util.ArrayItem;

public class KernelSequence extends ArrayItem<Number> {
	public KernelSequence(Number[] values) {
		super(values, Number[]::new);
	}

	public static KernelSequence of(Expression<?> exp, IndexValues values, int len) {
		return new KernelSequence(values.apply(exp).kernelSeq(len));
	}

	public static KernelSequence of(Number[] values) {
		return new KernelSequence(values);
	}
}
