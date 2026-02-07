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

package org.almostrealism.audio.arrange;

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedChromosome;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

public class AutomationManager implements Setup, CellFeatures {
	public static final int GENE_LENGTH = 6;

	private final ProjectedChromosome chromosome;
	private final TimeCell clock;
	private final DoubleSupplier measureDuration;
	private final int sampleRate;

	private final PackedCollection scale;
	private final double r = 1.0;
	private final double p = 0.5;

	public AutomationManager(ProjectedChromosome chromosome, TimeCell clock,
							 DoubleSupplier measureDuration, int sampleRate) {
		this.chromosome = chromosome;
		this.clock = clock;
		this.measureDuration = measureDuration;
		this.sampleRate = sampleRate;
		this.scale = PackedCollection.factory().apply(1);
	}

	protected Producer<PackedCollection> time(Producer<PackedCollection> position, Producer<PackedCollection> phase) {
		phase = c(2.0).multiply(phase).subtract(c(1.0)).multiply(c(p));
		return add(position, phase);
	}

	protected Producer<PackedCollection> time(Producer<PackedCollection> phase) {
		return time(divide(clock.time(sampleRate), cp(scale)), phase);
	}

	public Producer<PackedCollection> getAggregatedValue(Gene<PackedCollection> gene,
															Producer<PackedCollection> scale,
															double offset) {
		return getAggregatedValue(
				gene.valueAt(0).getResultant(c(1.0)),
				gene.valueAt(1).getResultant(c(1.0)),
				gene.valueAt(2).getResultant(c(1.0)),
				applyScale(gene.valueAt(3).getResultant(c(1.0)), scale),
				applyScale(gene.valueAt(4).getResultant(c(1.0)), scale),
				applyScale(gene.valueAt(5).getResultant(c(1.0)), scale),
				c(offset));
	}

	public Producer<PackedCollection> getAggregatedValueAt(Producer<PackedCollection> position,
															  Gene<PackedCollection> gene, double offset) {
		return getAggregatedValueAt(
				position,
				gene.valueAt(0).getResultant(c(1.0)),
				gene.valueAt(1).getResultant(c(1.0)),
				gene.valueAt(2).getResultant(c(1.0)),
				gene.valueAt(3).getResultant(c(1.0)),
				gene.valueAt(4).getResultant(c(1.0)),
				gene.valueAt(5).getResultant(c(1.0)),
				c(offset));
	}

	public Producer<PackedCollection> getAggregatedValue(
			Producer<PackedCollection> shortPeriodPhase,
			Producer<PackedCollection> longPeriodPhase,
			Producer<PackedCollection> mainPhase,
			Producer<PackedCollection> shortPeriodMagnitude,
			Producer<PackedCollection> longPeriodMagnitude,
			Producer<PackedCollection> mainMagnitude,
			Producer<PackedCollection> offset) {
		Producer<PackedCollection> shortPeriod = applyMagnitude(shortPeriodMagnitude, getShortPeriodValue(shortPeriodPhase));
		Producer<PackedCollection> longPeriod = applyMagnitude(longPeriodMagnitude, getLongPeriodValue(longPeriodPhase));
		Producer<PackedCollection> main = multiply(mainMagnitude, getMainValue(mainPhase, offset));

		return multiply(main, multiply(shortPeriod, longPeriod));
	}

	public Producer<PackedCollection> getAggregatedValueAt(
			Producer<PackedCollection> position,
			Producer<PackedCollection> shortPeriodPhase,
			Producer<PackedCollection> longPeriodPhase,
			Producer<PackedCollection> mainPhase,
			Producer<PackedCollection> shortPeriodMagnitude,
			Producer<PackedCollection> longPeriodMagnitude,
			Producer<PackedCollection> mainMagnitude,
			Producer<PackedCollection> offset) {
		Producer<PackedCollection> shortPeriod = applyMagnitude(shortPeriodMagnitude, getShortPeriodValueAt(position, shortPeriodPhase));
		Producer<PackedCollection> longPeriod = applyMagnitude(longPeriodMagnitude, getLongPeriodValueAt(position, longPeriodPhase));
		Producer<PackedCollection> main = multiply(mainMagnitude, getMainValueAt(position, mainPhase, offset));

		return multiply(main, multiply(shortPeriod, longPeriod));
	}

	protected Producer<PackedCollection> applyMagnitude(Producer<PackedCollection> magnitude,
														   Producer<PackedCollection> value) {
		return multiply(value, magnitude).add(c(1.0).subtract(magnitude));
	}

	protected Producer<PackedCollection> applyScale(Producer<PackedCollection> value, Producer<PackedCollection> scale) {
		return scale == null ? value : multiply(value, scale);
	}

	public Producer<PackedCollection> getMainValue(Producer<PackedCollection> phase, Producer<PackedCollection> offset) {
		return getMainValueAt(time(phase), offset);
	}

	public Producer<PackedCollection> getMainValueAt(Producer<PackedCollection> position,
														Producer<PackedCollection> phase,
														Producer<PackedCollection> offset) {
		return getMainValueAt(time(position, phase), offset);
	}

	public Producer<PackedCollection> getMainValueAt(Producer<PackedCollection> time, Producer<PackedCollection> offset) {
		Producer<PackedCollection> v = c(0.1 * r).multiply(time).pow(c(3.0));
		v = rectify(add(v, offset));
		return multiply(v, c(0.01));
	}

	public Producer<PackedCollection> getLongPeriodValue(Producer<PackedCollection> phase) {
		return getLongPeriodValueAt(time(phase));
	}

	public Producer<PackedCollection> getLongPeriodValueAt(Producer<PackedCollection> position, Producer<PackedCollection> phase) {
		return getLongPeriodValueAt(time(position, phase));
	}

	public Producer<PackedCollection> getLongPeriodValueAt(Producer<PackedCollection> time) {
		return c(0.95).add(sin(multiply(time, c(2.0 * r)))).multiply(c(0.05));
	}

	public Producer<PackedCollection> getShortPeriodValue(Producer<PackedCollection> phase) {
		return getShortPeriodValueAt(time(phase));
	}

	public Producer<PackedCollection> getShortPeriodValueAt(Producer<PackedCollection> position, Producer<PackedCollection> phase) {
		return getShortPeriodValueAt(time(position, phase));
	}

	public Producer<PackedCollection> getShortPeriodValueAt(Producer<PackedCollection> time) {
		return c(1.0).add(sin(multiply(time, c(16.0 * r))).multiply(c(-0.04)));
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("AutomationManager Setup");
		setup.add(() -> () -> {
			scale.set(0, measureDuration.getAsDouble());
		});
		return setup;
	}
}
