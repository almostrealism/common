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

package org.almostrealism.heredity;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * A {@link Gene} that maps continuous values to discrete choices.
 *
 * <p>This class wraps an underlying gene and transforms its continuous factor values
 * into indices for selecting from a collection of discrete choices. This is useful
 * when you need genetic control over discrete decisions (e.g., selecting from a
 * set of strategies, configurations, or components).
 *
 * <h2>Value Transformation</h2>
 * <p>The transformation works as follows:
 * <ol>
 *   <li>Get the continuous value from the underlying gene (typically in [0, 1])</li>
 *   <li>Multiply by the number of choices to get an index</li>
 *   <li>Use that index to select from the choices collection</li>
 * </ol>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Define discrete choices
 * PackedCollection<?> strategies = new PackedCollection<>(3);  // 3 strategies
 * strategies.setMem(0, 1.0, 2.0, 3.0);  // Strategy values
 *
 * // Create underlying gene that produces values in [0, 1]
 * Gene<PackedCollection<?>> continuousGene = HeredityFeatures.getInstance().g(0.0, 0.5, 1.0);
 *
 * // Create choice gene
 * ChoiceGene choiceGene = new ChoiceGene(continuousGene, strategies);
 *
 * // Factor at position 0 with value 0.0 selects choice 0
 * // Factor at position 1 with value 0.5 selects choice 1
 * // Factor at position 2 with value ~1.0 selects choice 2
 * Factor<PackedCollection<?>> factor = choiceGene.valueAt(1);
 * }</pre>
 *
 * @see ProjectedChromosome#addChoiceGene(PackedCollection, int)
 * @see Gene
 * @see GeneParameters
 */
public class ChoiceGene implements Gene<PackedCollection<?>>, GeneParameters, ScalarFeatures, CollectionFeatures {
	private PackedCollection<?> choices;
	private Gene<PackedCollection<?>> values;

	/**
	 * Constructs a new {@code ChoiceGene} that wraps the given gene and maps to the given choices.
	 *
	 * @param values the underlying gene providing continuous values
	 * @param choices the collection of discrete choices to select from
	 */
	public ChoiceGene(Gene<PackedCollection<?>> values, PackedCollection<?> choices) {
		this.choices = choices;
		this.values = values;
	}

	/**
	 * Returns the parameters from the underlying gene.
	 * <p>Requires the underlying gene to implement {@link GeneParameters}.
	 *
	 * @return the parameters collection
	 * @throws ClassCastException if the underlying gene doesn't implement GeneParameters
	 */
	@Override
	public PackedCollection<?> getParameters() {
		return ((GeneParameters) values).getParameters();
	}

	/**
	 * Returns the parameter ranges from the underlying gene.
	 * <p>Requires the underlying gene to implement {@link GeneParameters}.
	 *
	 * @return the parameter ranges collection
	 * @throws ClassCastException if the underlying gene doesn't implement GeneParameters
	 */
	@Override
	public PackedCollection<?> getParameterRanges() {
		return ((GeneParameters) values).getParameterRanges();
	}

	/**
	 * Returns the factor at the specified position.
	 * <p>The returned factor transforms the continuous value from the underlying gene
	 * into a discrete choice selection.
	 *
	 * @param pos the zero-based position of the factor
	 * @return a factor that produces a discrete choice based on the underlying continuous value
	 */
	@Override
	public Factor<PackedCollection<?>> valueAt(int pos) {
		return new Factor<>() {
			@Override
			public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
				value = values.valueAt(pos).getResultant(value);
				return c(shape(1), p(choices), multiply(value, c(choices.getMemLength())));
			}

			@Override
			public String signature() {
				return Signature.of(values.valueAt(pos));
			}
		};
	}

	/**
	 * Returns the number of factors in this gene.
	 * <p>This equals the number of factors in the underlying gene.
	 *
	 * @return the number of factors
	 */
	@Override
	public int length() { return values.length(); }
}
