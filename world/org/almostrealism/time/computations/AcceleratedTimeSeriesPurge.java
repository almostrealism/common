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

package org.almostrealism.time.computations;

import io.almostrealism.code.ExplicitScope;
import io.almostrealism.code.Scope;
import org.almostrealism.hardware.DynamicAcceleratedOperationAdapter;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.function.Consumer;

public class AcceleratedTimeSeriesPurge extends DynamicAcceleratedOperationAdapter {
	public AcceleratedTimeSeriesPurge(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors) {
		super(new Producer[] { series, cursors });
	}

	@Override
	public Scope<Void> getScope(NameProvider provider) {
		ExplicitScope<Void> scope = new ExplicitScope<>(this);

		String bank0 = getArgumentValueName(0, 0); // "bank[bankOffset]";
		String bank1 = getArgumentValueName(0, 1); // "bank[bankOffset + 1]";
		String banki = getArgumentValueName(0, "2 * i"); // "bank[2 * i]";
		String cursor0 = getArgumentValueName(1, 0); // "cursors[cursorsOffset]";

		Consumer<String> code = scope.code();
		code.accept("if (" + bank1 + " - " + bank0 + " <= 0) return;\n");
		code.accept("for (int i = " + bank0 + " + 1; i < " + bank1 + "; i++) {\n");
		code.accept("	if (" + banki + " > " + cursor0 + ") {\n");
		code.accept("		" + bank0 + " = i - 1;\n");
		code.accept("		break;\n");
		code.accept("	}\n");
		code.accept("}\n");
		return scope;
	}
}
