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

package org.almostrealism.hardware;

import io.almostrealism.relation.Delegated;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.OutputVariablePreservationArgumentMap;
import io.almostrealism.relation.Provider;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Optional;
import java.util.function.Supplier;

public class ProviderAwareArgumentMap<S, A> extends OutputVariablePreservationArgumentMap<S, A> implements ConsoleFeatures {
	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> arg = super.get(key, p);
		if (arg != null) return arg;

		if (key instanceof Delegated<?> && ((Delegated) key).getDelegate() instanceof PassThroughProducer<?>) {
			PassThroughProducer param = (PassThroughProducer) ((Delegated) key).getDelegate();

			Optional<ArrayVariable<A>> passThrough = get(v -> {
				if (!(v instanceof Delegated<?>)) return false;
				if (!(((Delegated) v).getDelegate() instanceof PassThroughProducer)) return false;
				return ((PassThroughProducer) ((Delegated) v).getDelegate()).getReferencedArgumentIndex() == param.getReferencedArgumentIndex();
			}, p);

			if (passThrough.isPresent())
				return passThrough.get();
		}

		Object provider = key.get();
		if (!(provider instanceof Provider)) return null;

		Object value = ((Provider) provider).get();

		return get(supplier -> {
			Object v = supplier.get();
			if (!(v instanceof Provider)) return false;
			return ((Provider) v).get() == value;
		}, p).orElse(null);
	}

	@Override
	public Console console() { return Hardware.console; }
}
