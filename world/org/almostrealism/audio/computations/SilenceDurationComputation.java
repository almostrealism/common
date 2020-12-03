/*
 * Copyright 2020 Michael Murray
 *
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

package org.almostrealism.audio.computations;

import io.almostrealism.code.ExplicitScope;
import io.almostrealism.code.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicAcceleratedOperationAdapter;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.Producer;

import java.util.function.Consumer;

public class SilenceDurationComputation extends DynamicAcceleratedOperationAdapter {
	public SilenceDurationComputation(Producer<Scalar> silenceDuration, Producer<Scalar> silenceSettings, Producer<Scalar> value) {
		super(new Producer[] { silenceDuration, silenceSettings, value });
	}

	@Override
	public Scope<Void> getScope(NameProvider provider) {
		ExplicitScope<Void> scope = new ExplicitScope<>(this);

		String value = getArgumentValueName(2, 0);
		String min = getArgumentValueName(1, 0);
		String duration = getArgumentValueName(0, 0);

		Consumer<String> code = scope.code();
		code.accept("if (" + value + " > " + min + ") " + duration + " = 0;\n");
		code.accept("if (" + value + " <= " + min + ") " + duration + " = " + duration + " + 1;\n");
		return scope;
	}
}
