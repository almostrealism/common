/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.heredity;

import org.almostrealism.collect.PackedCollection;

/**
 * An interface for genes that expose their underlying parameters.
 *
 * <p>This interface is implemented by genes that have configurable parameters
 * (such as {@link ProjectedGene}) and need to expose those parameters for
 * inspection, modification, or breeding operations.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * GeneParameters gene = ...;
 *
 * // Get the parameters
 * PackedCollection<?> params = gene.getParameters();
 *
 * // Get the valid ranges for each parameter
 * PackedCollection<?> ranges = gene.getParameterRanges();
 * // ranges[i] typically contains [min, max] for parameter i
 * }</pre>
 *
 * @see ChoiceGene
 */
public interface GeneParameters {
	/**
	 * Returns the underlying parameters for this gene.
	 *
	 * @return the parameter collection
	 */
	PackedCollection<?> getParameters();

	/**
	 * Returns the valid ranges for each parameter.
	 * <p>Typically structured as pairs of [min, max] values for each parameter.
	 *
	 * @return the parameter ranges collection
	 */
	PackedCollection<?> getParameterRanges();
}
