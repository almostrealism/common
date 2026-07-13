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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;

/**
 * A {@link Gene} that produces factor values by projecting source data through weighted transformations.
 *
 * <p>This class implements a gene where each factor value is computed by taking the dot product
 * of source data with learned weights, then applying a triangular wave function to map the result
 * to a bounded range. This approach allows continuous source parameters to produce smoothly
 * varying factor values suitable for evolutionary optimization.
 *
 * <h2>Value Computation</h2>
 * <p>For each factor position, the value is computed as:
 * <ol>
 *   <li>Calculate dot product: {@code sum(source[i] * weights[pos][i])}</li>
 *   <li>Apply modulo to create periodicity: {@code value % 2.0}</li>
 *   <li>Apply triangular wave: ramp up from 0 to 0.5, then down from 0.5 to 1.0</li>
 *   <li>Scale to the configured range: {@code [min, max]}</li>
 * </ol>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create source data (shared across genes)
 * PackedCollection source = new PackedCollection(100);  // 100 parameters
 *
 * // Create weights for this gene (5 factors, each weighted by 100 source values)
 * PackedCollection weights = new PackedCollection(shape(5, 100).traverse(1));
 *
 * // Create the projected gene
 * ProjectedGene gene = new ProjectedGene(source, weights);
 *
 * // Set range for factor 0
 * gene.setRange(0, 0.0, 1.0);  // Default range
 *
 * // Initialize weights with random values
 * gene.initWeights(42L);
 *
 * // Compute factor values from source
 * gene.refreshValues();
 *
 * // Access factor
 * Factor<PackedCollection> factor = gene.valueAt(0);
 * }</pre>
 *
 * @see TransformableGene
 * @see ProjectedChromosome
 * @see ProjectedGenome
 */
public class ProjectedGene extends TransformableGene implements VectorFeatures {
	/** The shared source data from which this gene projects its factor values. */
	private final PackedCollection source;
	/** Projection weight matrix; rows correspond to factors, columns to source elements. */
	private final PackedCollection weights;
	/** Per-factor value ranges used to clamp or scale projected outputs. */
	private final PackedCollection ranges;

	/** Cache of the most recently computed factor values for this gene. */
	private PackedCollection values;

	/**
	 * Constructs a new {@code ProjectedGene} with the specified source data and weights.
	 *
	 * <p>The weights must be a 2D collection where the first dimension corresponds to the
	 * number of factors in this gene, and the second dimension must match the source length.
	 *
	 * @param source the source data to project (must be 1D)
	 * @param weights the projection weights (shape: [numFactors, sourceLength])
	 * @throws IllegalArgumentException if source is not 1D, weights is not 2D,
	 *         or weight dimensions don't match source length
	 */
	public ProjectedGene(PackedCollection source,
						 PackedCollection weights) {
		super(weights.getShape().length(0));
		this.source = source;
		this.weights = weights;
		this.ranges = new PackedCollection(shape(length(), 2)).traverse(1);
		this.values = new PackedCollection(shape(length()));

		int sourceLength = source.getShape().length(0);

		if (source.getShape().getDimensions() != 1 ||
				weights.getShape().getDimensions() != 2 ||
				weights.getShape().length(1) != sourceLength ||
				weights.getAtomicMemLength() != sourceLength) {
			throw new IllegalArgumentException();
		}

		initRanges();
	}

	/**
	 * Initializes the weights with normalized random values.
	 * <p>Each weight vector (row) is initialized with random normal values and then
	 * normalized to unit length.
	 *
	 * @param seed the random seed for reproducible initialization
	 */
	public void initWeights(long seed) {
		randn(shape(weights), new Random(seed)).into(weights).evaluate();
		for (int pos = 0; pos < length(); pos++) {
			PackedCollection row = weights.get(pos);
			a(cp(row), cp(row).divide(sqrt(cp(row).multiply(cp(row)).sum()))).get().run();
		}
	}

	/**
	 * Recomputes all factor values by projecting the current source data through the weights.
	 * <p>This method should be called after the source data has been modified to update
	 * the factor values. The computation applies a triangular wave function to map the
	 * dot product result to the configured range for each factor.
	 *
	 * <p>The projection, the triangular wave, and the range mapping are one computation
	 * graph over the gene's collections: the dot products are a matrix-vector product, the
	 * wave is a positive mod followed by a comparison select, and the range bounds are read
	 * from the columns of {@link #ranges}. Every operand is a runtime argument, so a single
	 * compiled kernel serves every gene and every refresh.
	 */
	public void refreshValues() {
		int len = length();
		int sourceLength = source.getShape().length(0);

		CollectionProducer projected = MatrixFeatures.getInstance()
				.matmul(cp(weights).reshape(shape(len, sourceLength)), cp(source))
				.reshape(shape(len));

		CollectionProducer value = mod(mod(projected, c(2.0)).add(c(2.0)), c(2.0));
		CollectionProducer phase = value.divide(c(2.0));
		CollectionProducer wave = greaterThan(phase, c(0.5),
				c(2.0).subtract(phase.multiply(c(2.0))),
				phase.multiply(c(2.0)));

		CollectionProducer bounds = cp(ranges).reshape(shape(len, 2));
		CollectionProducer start = subset(shape(len, 1), bounds, 0, 0).reshape(shape(len));
		CollectionProducer end = subset(shape(len, 1), bounds, 0, 1).reshape(shape(len));

		a(cp(values), start.add(wave.multiply(end.subtract(start)))).get().run();
	}

	/**
	 * Returns the shape of the source data.
	 *
	 * @return the traversal policy describing the source shape
	 */
	public TraversalPolicy getInputShape() { return source.getShape(); }

	/**
	 * Initializes all factor ranges to the default [0.0, 1.0].
	 */
	protected void initRanges() {
		for (int i = 0; i < length(); i++) {
			ranges.get(i).setMem(0.0, 1.0);
		}
	}

	/**
	 * Sets the output range for a specific factor position.
	 * <p>The computed value will be scaled to lie within [min, max].
	 *
	 * @param index the zero-based factor position
	 * @param min the minimum output value
	 * @param max the maximum output value
	 */
	public void setRange(int index, double min, double max) {
		ranges.get(index).fill(min, max);
	}

	/**
	 * Returns the factor at the specified position.
	 * <p>The returned factor applies any configured transformations and optionally
	 * multiplies by an input value if provided.
	 *
	 * @param pos the zero-based position of the factor
	 * @return a factor that produces the projected and transformed value
	 */
	@Override
	public Factor<PackedCollection> valueAt(int pos) {
		return in -> {
			Producer<PackedCollection> result = transform(pos, cp(values.range(shape(1), pos)));
			return in == null ? result : multiply(in, result);
		};
	}

	/**
	 * Returns the number of factors in this gene.
	 *
	 * @return the number of factors (equal to the first dimension of weights)
	 */
	@Override
	public int length() { return weights.getShape().length(0); }

	/**
	 * Returns the values collection for this gene.
	 * <p>This is the collection that holds the computed factor values after
	 * {@link #refreshValues()} has been called.
	 *
	 * @return the values collection
	 */
	PackedCollection getValues() { return values; }

	/**
	 * Replaces the values collection with a new one, typically a view into a
	 * consolidated buffer.
	 * <p>This enables multiple genes to share a single contiguous
	 * {@link PackedCollection} as their backing storage. When gene values are
	 * consolidated, {@link #refreshValues()} writes through the view to the
	 * shared buffer, and {@link #valueAt(int)} creates sub-views that resolve
	 * to the same root delegate during scope argument deduplication.
	 *
	 * @param newValues the replacement values collection (must have the same length)
	 * @throws IllegalArgumentException if the lengths do not match
	 * @see ProjectedChromosome#consolidateGeneValues()
	 */
	void replaceValues(PackedCollection newValues) {
		if (newValues.getMemLength() != values.getMemLength()) {
			throw new IllegalArgumentException(
					"Values length mismatch: expected " + values.getMemLength() +
					", got " + newValues.getMemLength());
		}

		this.values = newValues;
	}
}
