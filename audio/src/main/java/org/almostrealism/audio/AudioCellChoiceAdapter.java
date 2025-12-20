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

package org.almostrealism.audio;

import io.almostrealism.code.Computation;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.Switch;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Abstract adapter for dynamically selecting between multiple audio processing cells at runtime.
 *
 * <p>AudioCellChoiceAdapter enables switching between different audio processors based on a
 * decision value, supporting both parallel and sequential execution modes. This is useful for
 * scenarios where the processing path should be selected dynamically, such as instrument
 * switching or effect routing.</p>
 *
 * <h2>Execution Modes</h2>
 * <ul>
 *   <li><b>Parallel mode</b>: All cells execute simultaneously, output is selected based on decision</li>
 *   <li><b>Sequential mode</b>: Only the selected cell executes via Switch computation</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>This class is typically used through its concrete subclasses:</p>
 * <ul>
 *   <li>{@link DynamicAudioCell} - Parallel execution with independent data per choice</li>
 *   <li>{@link PolymorphicAudioCell} - Sequential execution with shared data</li>
 * </ul>
 *
 * @see DynamicAudioCell
 * @see PolymorphicAudioCell
 * @see org.almostrealism.algebra.computations.Switch
 */
public abstract class AudioCellChoiceAdapter extends CollectionTemporalCellAdapter implements CellFeatures {

	private CollectionProducer decision;
	private final List<CollectionTemporalCellAdapter> cells;
	private final boolean parallel;

	private final PackedCollection storage;

	public AudioCellChoiceAdapter(CollectionProducer decision,
								  IntFunction<PolymorphicAudioData> data,
								  List<Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>> choices,
								  boolean parallel) {
		this(decision, IntStream.range(0, choices.size())
			.mapToObj(i -> choices.get(i).apply(data.apply(i)))
			.collect(Collectors.toList()), parallel);
	}

	public AudioCellChoiceAdapter(CollectionProducer decision,
								  List<CollectionTemporalCellAdapter> choices,
								  boolean parallel) {
		this.decision = decision;
		this.cells = choices;
		this.parallel = parallel;

		if (parallel) {
			storage = new PackedCollection(choices.size()).traverse(1);
			initParallel();
		} else {
			storage = new PackedCollection(1).traverse(1);
			cells.forEach(cell -> cell.setReceptor(a(p(storage.get(0)))));
		}
	}

	public void setDecision(CollectionProducer decision) {
		this.decision = decision;
	}

	private void initParallel() {
		getCellSet().forEach(c ->
				c.setReceptor(a(indexes(c).mapToObj(storage::get).map(this::p).toArray(Producer[]::new))));
	}

	private IntStream indexes(CollectionTemporalCellAdapter c) {
		return IntStream.range(0, cells.size()).filter(i -> cells.get(i) == c);
	}

	protected Set<CollectionTemporalCellAdapter> getCellSet() {
		HashSet<CollectionTemporalCellAdapter> set = new HashSet<>();

		n: for (CollectionTemporalCellAdapter n : cells) {
			for (CollectionTemporalCellAdapter c : set) {
				if (c == n) continue n;
			}

			set.add(n);
		}

		return set;
	}

	@Override
	public Supplier<Runnable> setup() {
		if (parallel) {
			return getCellSet().stream().map(Cell::setup).collect(OperationList.collector());
		} else {
			return new Switch(decision,
					cells.stream().map(cell -> (Computation<?>) cell.setup())
							.collect(Collectors.toList()));
		}
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		OperationList push = new OperationList("AudioCellChoiceAdapter Push");

		if (parallel) {
			getCellSet().stream().map(cell -> cell.push(protein)).forEach(push::add);
			push.add(new Switch(decision, storage.stream()
					.map(v -> (Computation<?>) getReceptor().push(p(v)))
					.collect(Collectors.toList())));
		} else {
			push.add(new Switch(decision,
					cells.stream().map(cell -> (Computation<?>) cell.push(protein))
							.collect(Collectors.toList())));
			push.add(getReceptor().push(p(storage.get(0))));
		}

		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		if (parallel) {
			return getCellSet().stream().map(CollectionTemporalCellAdapter::tick).collect(OperationList.collector());
		} else {
			return new Switch(decision,
					cells.stream().map(CollectionTemporalCellAdapter::tick).map(v -> (Computation<?>) v)
							.collect(Collectors.toList()));
		}
	}

	@Override
	public void reset() {
		super.reset();
		getCellSet().forEach(Cell::reset);
	}
}
