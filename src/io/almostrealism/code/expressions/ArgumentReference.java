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

package io.almostrealism.code.expressions;

import io.almostrealism.code.Argument;
import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Variable;

/**
 * {@link ArgumentReference} is used to reference a declared {@link Argument}.
 * {@link CodePrintWriter} implementations should encode the data as a
 * {@link String}, but unlike a normal {@link String} {@link Variable} the
 * text does not appear in quotes.
 */
public class ArgumentReference extends InstanceReference {
	public ArgumentReference(Argument<?> arg) {
		super(arg);
	}

	@Override
	public Argument getReferent() { return (Argument) super.getReferent(); }
}
