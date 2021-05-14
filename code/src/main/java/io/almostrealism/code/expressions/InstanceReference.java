/*
 * Copyright 2021 Michael Murray
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

package io.almostrealism.code.expressions;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.CollectionUtils;
import io.almostrealism.code.Variable;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T> extends Expression<T> {
	private Variable var;

	public InstanceReference(Variable<T, ?> v) {
		this(v.getType(), v.getName(), v);
		this.var = v;
	}

	public InstanceReference(Variable<T, ?> v, Variable... dependencies) {
		this(v.getType(), v.getName(), CollectionUtils.include(new Variable[0], v, dependencies));
		this.var = v;
	}

	public InstanceReference(Class<T> type, String varName, Variable... dependencies) {
		super(type, varName, dependencies);
	}

	public Variable getReferent() { return var; }

	public Variable assign(Expression exp) {
		return new Variable(getExpression(), false, exp);
	}
}
