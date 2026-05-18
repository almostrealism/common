/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;

/**
 * Marker interface for components that hold learnable parameters.
 *
 * <p>Any {@link org.almostrealism.graph.Cell} or {@link BackPropagation} implementation
 * that updates weights during training should implement this interface so that the
 * owning layer or model can install the appropriate {@link ParameterUpdate} strategy
 * before the first backward pass.</p>
 *
 * @see ParameterUpdate
 * @see DefaultGradientPropagation
 * @author Michael Murray
 */
public interface Learning {

	/**
	 * Sets the strategy used to apply gradient updates to this component's learnable parameters.
	 *
	 * @param update the parameter update strategy to install; must not be {@code null} when
	 *               the first backward pass is executed
	 */
	void setParameterUpdate(ParameterUpdate<PackedCollection> update);
}
