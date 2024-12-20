/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.layers;

import org.almostrealism.collect.CollectionFeatures;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.io.Describable;

import java.util.Optional;
import java.util.function.Supplier;

public interface Component extends Describable {

	TraversalPolicy getOutputShape();

	@Override
	default String describe() {
		return getOutputShape().toStringDetail();
	}

	static <T> Optional<TraversalPolicy> shape(T v) {
		if (v instanceof Component) {
			return Optional.of(((Component) v).getOutputShape());
		} else if (v instanceof Supplier) {
			return Optional.of(CollectionFeatures.getInstance().shape((Supplier) v));
		} else {
			return Optional.empty();
		}
	}
}
