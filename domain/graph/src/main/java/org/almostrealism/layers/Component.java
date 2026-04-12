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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.io.Describable;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A base interface for components in the neural network architecture that produce
 * output with a defined shape. Component provides the foundation for all neural
 * network building blocks that need to declare their output dimensions.
 *
 * <p>Component extends:</p>
 * <ul>
 *   <li>{@link Destroyable} - For resource cleanup</li>
 *   <li>{@link Describable} - For human-readable descriptions</li>
 * </ul>
 *
 * <h2>Shape Information</h2>
 * <p>The primary contract of Component is {@link #getOutputShape()}, which returns
 * the shape of data produced by this component. This information is used for:</p>
 * <ul>
 *   <li>Validating connections between layers</li>
 *   <li>Allocating memory for intermediate results</li>
 *   <li>Debugging and visualization</li>
 * </ul>
 *
 * <h2>Utility Methods</h2>
 * <p>The static {@link #shape(Object)} method provides a convenient way to extract
 * shape information from various types of objects.</p>
 *
 * @see Layer
 * @see org.almostrealism.model.Block
 * @author Michael Murray
 */
public interface Component extends Destroyable, Describable {

	/**
	 * Returns the shape of the output produced by this component.
	 *
	 * @return the output shape as a TraversalPolicy
	 */
	TraversalPolicy getOutputShape();

	/**
	 * Returns a human-readable description of this component.
	 * Default implementation returns detailed shape information.
	 *
	 * @return a string describing this component
	 */
	@Override
	default String describe() {
		return getOutputShape().toStringDetail();
	}

	/**
	 * Extracts the output shape from a value if possible.
	 *
	 * @param <T> the type of the value
	 * @param v the value to extract shape from
	 * @return an Optional containing the shape if available, empty otherwise
	 */
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
