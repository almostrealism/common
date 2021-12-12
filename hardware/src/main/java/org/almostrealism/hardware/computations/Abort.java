/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.Scope;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Bytes;

import java.util.Optional;
import java.util.function.Supplier;

public class Abort extends DynamicOperationComputationAdapter<MemoryData> {
	private static MemoryData abortFallback;

	static {
		abortFallback = new Bytes(1);
		abortFallback.setMem(0.0);
	}
	public Abort(MemoryData control) {
		super(() -> new Provider(control));
	}

	public Abort(Supplier<MemoryData> control) {
		super(() -> args -> Optional.ofNullable(control.get()).orElse(abortFallback));
	}

	@Override
	public Scope<Void> getScope() {
		HybridScope<Void> scope = new HybridScope<>(this);
		scope.code().accept("if (");
		scope.code().accept(getArgument(0).get("0").getExpression());
		scope.code().accept(" > 0) { return; }");
		return scope;
	}
}
