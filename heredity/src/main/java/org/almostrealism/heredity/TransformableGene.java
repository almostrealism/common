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

package org.almostrealism.heredity;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.UnaryOperator;

/**
 * An abstract {@link Gene} implementation that supports value transformations.
 *
 * <p>This class provides a foundation for genes that need to apply transformations
 * to their factor values. It supports two types of transformations:
 * <ul>
 *   <li><b>Position-specific transformations</b> - Applied only to values at a specific position</li>
 *   <li><b>Global transformation</b> - Applied to all values after any position-specific transform</li>
 * </ul>
 *
 * <p>When both types of transformations are present for a position, the position-specific
 * transformation is applied first, followed by the global transformation.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a transformable gene subclass
 * TransformableGene gene = new MyTransformableGene(10);
 *
 * // Set position-specific transformation (e.g., sigmoid for position 0)
 * gene.setTransform(0, producer -> sigmoid(producer));
 *
 * // Set global transformation applied to all positions
 * gene.setTransform(producer -> normalize(producer));
 *
 * // When accessing factor at position 0:
 * // Result = global(position-specific(raw-value))
 * //        = normalize(sigmoid(raw-value))
 * }</pre>
 *
 * @see ProjectedGene
 * @see Gene
 */
public abstract class TransformableGene implements Gene<PackedCollection<?>> {

	private UnaryOperator<Producer<PackedCollection<?>>> transform;
	private UnaryOperator<Producer<PackedCollection<?>>> transforms[];

	/**
	 * Constructs a new {@code TransformableGene} with the specified number of factors.
	 *
	 * @param length the number of factors in this gene
	 */
	public TransformableGene(int length) {
		this.transforms = new UnaryOperator[length];
	}

	/**
	 * Returns the global transformation applied to all factor values.
	 *
	 * @return the global transformation, or {@code null} if none is set
	 */
	public UnaryOperator<Producer<PackedCollection<?>>> getTransform() {
		return transform;
	}

	/**
	 * Sets the global transformation to be applied to all factor values.
	 * <p>This transformation is applied after any position-specific transformation.
	 *
	 * @param transform the global transformation function, or {@code null} to remove
	 */
	public void setTransform(UnaryOperator<Producer<PackedCollection<?>>> transform) {
		this.transform = transform;
	}

	/**
	 * Returns the position-specific transformation for the given factor position.
	 *
	 * @param pos the zero-based position of the factor
	 * @return the transformation for that position, or {@code null} if none is set
	 */
	public UnaryOperator<Producer<PackedCollection<?>>> getTransform(int pos) {
		return transforms[pos];
	}

	/**
	 * Sets a position-specific transformation for the given factor position.
	 * <p>This transformation is applied before the global transformation.
	 *
	 * @param pos the zero-based position of the factor
	 * @param transform the transformation function, or {@code null} to remove
	 */
	public void setTransform(int pos, UnaryOperator<Producer<PackedCollection<?>>> transform) {
		this.transforms[pos] = transform;
	}

	/**
	 * Applies the appropriate transformations to a value at the specified position.
	 * <p>If a position-specific transformation exists, it is applied first.
	 * Then, if a global transformation exists, it is applied to the result.
	 *
	 * @param pos the zero-based position of the factor
	 * @param value the raw value to transform
	 * @return the transformed value
	 */
	protected Producer<PackedCollection<?>> transform(int pos, Producer<PackedCollection<?>> value) {
		if (transforms[pos] != null) value = transforms[pos].apply(value);
		return transform == null ? value : transform.apply(value);
	}
}
