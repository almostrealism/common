/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.notes;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.sources.ModularSourceAggregator;
import org.almostrealism.audio.sources.SourceAggregator;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class NoteAudioSourceAggregator implements CodeFeatures {
	public static boolean enableAdvancedAggregation = true;

	private final List<AggregatorChoice> aggregators;

	public NoteAudioSourceAggregator() {
		aggregators = new ArrayList<>();
		aggregators.add(new AggregatorChoice(new ModularSourceAggregator(
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.SOURCE), 6.0));

		if (enableAdvancedAggregation) {
			aggregators.add(new AggregatorChoice(new ModularSourceAggregator(
					ModularSourceAggregator.InputType.SOURCE,
					ModularSourceAggregator.InputType.SOURCE,
					ModularSourceAggregator.InputType.VOLUME_ENVELOPE), 3.0));
			aggregators.add(new AggregatorChoice(new ModularSourceAggregator(
					ModularSourceAggregator.InputType.SOURCE,
					ModularSourceAggregator.InputType.SOURCE,
					ModularSourceAggregator.InputType.FREQUENCY), 1.0));
			aggregators.add(new AggregatorChoice(new ModularSourceAggregator(
					ModularSourceAggregator.InputType.SOURCE,
					ModularSourceAggregator.InputType.FREQUENCY,
					ModularSourceAggregator.InputType.VOLUME_ENVELOPE), 2.0));
		}
	}

	protected double getTotalWeight() {
		return aggregators.stream().mapToDouble(AggregatorChoice::getWeight).sum();
	}

	public SourceAggregator getAggregator(Producer<PackedCollection> choice) {
		return (buffer, params, frequency, sources) -> () -> {
			Evaluable<PackedCollection> c = choice.get();
			List<Evaluable<? extends PackedCollection>> evals = aggregators.stream()
					.map(a -> a.getAggregator().aggregate(buffer, params, frequency, sources))
					.map(Process::optimized)
					.map(Supplier::get)
					.collect(Collectors.toUnmodifiableList());

			return args -> {
				double pos = c.evaluate(args).toDouble() * getTotalWeight();

				double cutoff = 0.0;

				for (int i = 0; i < evals.size(); i++) {
					cutoff += aggregators.get(i).getWeight();

					if (pos < cutoff) {
						return evals.get(i).evaluate(args);
					}
				}

				throw new IllegalArgumentException();
			};
		};
	}

	protected static class AggregatorChoice {
		private final SourceAggregator aggregator;
		private final double weight;

		public AggregatorChoice(SourceAggregator aggregator, double weight) {
			this.aggregator = aggregator;
			this.weight = weight;
		}

		public SourceAggregator getAggregator() { return aggregator; }
		public double getWeight() { return weight; }
	}
}
