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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.AdjustableDelayCell;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGene;
import org.almostrealism.time.computations.MultiOrderFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class EfxManager implements CellFeatures {
	public static boolean enableEfx = true;
	public static boolean enableAutomation = true;
	public static double maxWet = 0.5;
	public static double maxFeedback = 0.5;
	public static int filterOrder = 40;

	private final AutomationManager automation;

	private final ProjectedChromosome chromosome;
	private Chromosome<PackedCollection> delayTimes;
	private Chromosome<PackedCollection> delayLevels;
	private Chromosome<PackedCollection> delayAutomation;
	private final int channels;
	private List<Integer> wetChannels;

	private final DoubleSupplier beatDuration;
	private final int sampleRate;

	public EfxManager(ProjectedChromosome chromosome, int channels,
					  AutomationManager automation,
					  DoubleSupplier beatDuration, int sampleRate) {
		this.chromosome = chromosome;
		this.channels = channels;
		this.wetChannels = new ArrayList<>();
		IntStream.range(0, channels).forEach(this.wetChannels::add);

		this.automation = automation;

		this.beatDuration = beatDuration;
		this.sampleRate = sampleRate;

		init();
	}

	protected void init() {
		double[] choices = IntStream.range(0, 5)
				.mapToDouble(i -> Math.pow(2, i - 2))
				.mapToObj(d -> List.of(d, 1.5 * d))
				.flatMap(List::stream)
				.mapToDouble(d -> d)
				.toArray();

		PackedCollection c = new PackedCollection(choices.length);
		c.setMem(choices);

		delayTimes = chromosome(IntStream.range(0, channels)
				.mapToObj(i -> chromosome.addChoiceGene(c, 1))
				.collect(Collectors.toList()));

		delayLevels = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(4);
			if (maxWet != 1.0) g.setTransform(0, p -> multiply(p, c(maxWet)));
			if (maxFeedback != 1.0) g.setTransform(1, p -> multiply(p, c(maxFeedback)));
			return g;
		}).collect(Collectors.toList()));

		delayAutomation = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(AutomationManager.GENE_LENGTH);
			return g;
		}).collect(Collectors.toList()));
	}

	public List<Integer> getWetChannels() { return wetChannels; }
	public void setWetChannels(List<Integer> wetChannels) { this.wetChannels = wetChannels; }

	/**
	 * Applies effects to audio with optional external frame control.
	 *
	 * <p>When {@code frame} is provided, WaveCells are created with external
	 * frame control instead of internal clocks. This is essential for real-time
	 * rendering where the frame position must be controlled per-buffer.</p>
	 *
	 * @param channel       the channel to process
	 * @param audio         the audio producer
	 * @param totalDuration total duration in seconds (used for looping when frame is null)
	 * @param setup         setup operations list
	 * @param frame external frame position producer, or null for internal clock
	 * @return CellList with effects applied
	 */
	public CellList apply(ChannelInfo channel, Producer<PackedCollection> audio, double totalDuration,
						  OperationList setup, Producer<PackedCollection> frame) {
		if (!enableEfx || !wetChannels.contains(channel.getPatternChannel())) {
			return createCells(audio, totalDuration, frame);
		}

		CellList wet = createCells(applyFilter(channel, audio, setup), totalDuration, frame)
						.map(fc(i -> delayLevels.valueAt(channel.getPatternChannel(), 0)));
		CellList dry = createCells(audio, totalDuration, frame);

		Producer<PackedCollection> delay = delayTimes.valueAt(channel.getPatternChannel(), 0).getResultant(c(1.0));

		CellList delays = IntStream.range(0, 1)
				.mapToObj(i -> new AdjustableDelayCell(sampleRate,
						multiply(c(beatDuration.getAsDouble()), delay),
						c(1.0)))
				.collect(CellList.collector());

		IntFunction<Cell<PackedCollection>> auto =
				enableAutomation ?
						fc(i -> in -> {
							Producer<PackedCollection> value = automation
									.getAggregatedValue(delayAutomation.valueAt(channel.getPatternChannel()), null, 0.0);
							value = c(0.5).multiply(c(1.0).add(value));
							return multiply(in, value);
						}) :
						fi();

		wet = wet.m(auto, delays)
				.mself(fi(), i -> g(delayLevels.valueAt(channel.getPatternChannel(), 1)))
				.sum();

		CellList cells = cells(wet, dry).sum();
		return cells;
	}

	protected CellList createCells(Producer<PackedCollection> audio, double totalDuration) {
		return createCells(audio, totalDuration, null);
	}

	/**
	 * Creates WaveCells for the given audio producer.
	 *
	 * <p>When {@code frameProducer} is provided, creates WaveCells with external frame
	 * control (no internal clock). When null, creates WaveCells with internal clocks
	 * that loop over {@code totalDuration}.</p>
	 *
	 * @param audio         the audio producer
	 * @param totalDuration total duration in seconds (ignored when frameProducer is provided)
	 * @param frameProducer external frame position producer, or null for internal clock
	 * @return CellList containing WaveCells
	 */
	protected CellList createCells(Producer<PackedCollection> audio, double totalDuration,
								   Producer<PackedCollection> frameProducer) {
		if (frameProducer != null) {
			// Real-time mode: use external frame control
			Producer<PackedCollection> waveProducer = traverse(0, audio);
			return w(PolymorphicAudioData.supply(PackedCollection.factory()),
					shape(audio).getTotalSize(), frameProducer, waveProducer);
		} else {
			// Traditional mode: use internal clock with looping
			return w(PolymorphicAudioData.supply(PackedCollection.factory()),
					sampleRate, shape(audio).getTotalSize(),
					null, c(totalDuration), traverse(0, audio));
		}
	}

	protected Producer<PackedCollection> applyFilter(ChannelInfo channel, Producer<PackedCollection> audio, OperationList setup) {
		PackedCollection destination = PackedCollection.factory().apply(shape(audio).getTotalSize());

		Producer<PackedCollection> decision =
				delayLevels.valueAt(channel.getPatternChannel(), 2).getResultant(c(1.0));
		Producer<PackedCollection> cutoff = c(20000)
				.multiply(delayLevels.valueAt(channel.getPatternChannel(), 3).getResultant(c(1.0)));

		CollectionProducer lpCoefficients =
				lowPassCoefficients(cutoff, sampleRate, filterOrder)
						.reshape(1, filterOrder + 1);
		CollectionProducer hpCoefficients =
				highPassCoefficients(cutoff, sampleRate, filterOrder)
						.reshape(1, filterOrder + 1);

		Producer<PackedCollection> coefficients = choice(2,
				shape(filterOrder + 1), decision,
				concat(shape(2, filterOrder + 1), hpCoefficients, lpCoefficients));

		setup.add(a("efxFilter", cp(destination.each()),
					MultiOrderFilter.create(audio, coefficients)));
		return cp(destination);
	}
}
