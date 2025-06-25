/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Variable;
import io.almostrealism.uml.Signature;
import io.almostrealism.util.DescribableParent;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public abstract class ProducerComputationBase<I, O> extends ComputationBase<I, O, Evaluable<? extends O>> implements Operator<O> {

	@Override
	public Variable getOutputVariable() { return getArgument( 0); }

	public Evaluable<O> getDestination() {
		return (Evaluable<O>) getInputs().get(0).get();
	}

	/**
	 * Generates a unique signature for this {@link Computation} based on its name
	 * the signatures of its inputs.
	 * The signature is created by combining the computation name with the signatures
	 * of all inputs except the first one (assumed to be the destination), joined
	 * with colons.
	 *
	 * @return An MD5 hash of the combined signature string, or null if any input signature is null
	 *
	 * @see Signature#of(Object)
	 * @see Signature#md5(String)
	 */
	@Override
	public String signature() {
		List<String> signatures = getInputs().stream().skip(1)
				.map(Signature::of).collect(Collectors.toList());
		if (signatures.stream().anyMatch(Objects::isNull)) {
			// If any of the inputs do not provide signatures,
			// it is not possible to be certain about the signature
			// of this computation
			return null;
		}

		return Signature.md5(getName() + "|" + String.join(":", signatures));
	}

	@Override
	public String description() {
		Collection<Process<?, ?>> children = getChildren();
		if (children == null) return super.description();

		// The first child is normally the destination and not useful to include
		return description(children.stream().map(DescribableParent::description)
				.skip(1).collect(Collectors.toList()));
	}
}
