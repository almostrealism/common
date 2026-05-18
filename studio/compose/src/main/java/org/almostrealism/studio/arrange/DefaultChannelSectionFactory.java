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

package org.almostrealism.studio.arrange;
import org.almostrealism.music.arrange.ChannelSection;

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.filter.EnvelopeFeatures;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.studio.optimize.OptimizeFactorFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.graph.temporal.WaveCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.MemoryDataCopy;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGene;
import org.almostrealism.io.Console;
import org.almostrealism.time.Frequency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Factory that creates {@link DefaultChannelSectionFactory.Section} instances for each
 * pattern channel within a scene section. Each section applies volume rise-fall envelopes,
 * optional low-pass filtering, and sample-repeat scheduling driven by chromosome genes.
 */
public class DefaultChannelSectionFactory implements Setup, Destroyable,
						CellFeatures, EnvelopeFeatures, OptimizeFactorFeatures {
	/** When {@code true}, a volume rise-fall envelope is applied to each channel section. */
	public static boolean enableVolumeRiseFall = true;

	/** When {@code true}, a low-pass filter envelope is applied to wet channels. */
	public static boolean enableFilter = true;

	/** Maximum frequency rise value for the low-pass filter envelope, in Hz. */
	public static double MAX_FILTER_RISE = 18000;

	/** Candidate sample-repeat durations in beats. */
	public static final double[] repeatChoices = new double[] { 8, 16, 32 };

	/** Time cell driving the clock used by wave cells and envelope computations. */
	private TimeCell clock;

	/** GPU-resident buffer holding the current section duration in seconds. */
	private PackedCollection duration;

	/** Per-channel volume rise-fall gene values. */
	private final Chromosome<PackedCollection> volume;

	/** Per-channel volume exponent gene values. */
	private final Chromosome<PackedCollection> volumeExp;

	/** Per-channel low-pass filter rise-fall gene values. */
	private final Chromosome<PackedCollection> lowPassFilter;

	/** Per-channel low-pass filter exponent gene values. */
	private final Chromosome<PackedCollection> lowPassFilterExp;

	/** Per-channel sample repeat duration genes (chosen from {@link #repeatChoices}). */
	private final Chromosome<PackedCollection> simpleDuration;

	/** Per-channel repeat speed-up duration genes. */
	private final Chromosome<PackedCollection> simpleDurationSpeedUp;

	/** Index of the next channel section to be created. */
	private int channel;

	/** Total number of pattern channels. */
	private final int channels;

	/** Predicate indicating which channels receive wet (filter) processing. */
	private final IntPredicate wetChannels;

	/** Predicate indicating which channels use looping repeats. */
	private final IntPredicate repeatChannels;

	/** Supplier providing the current tempo frequency. */
	private final Supplier<Frequency> tempo;

	/** Supplier providing the current measure duration in seconds. */
	private final DoubleSupplier measureDuration;

	/** Length of each section in measures. */
	private final int length;

	/** Audio sample rate. */
	private final int sampleRate;

	/**
	 * Creates a default channel section factory.
	 *
	 * @param chromosome      chromosome from which gene segments are allocated
	 * @param channels        number of pattern channels
	 * @param wetChannels     predicate identifying channels that receive wet processing
	 * @param repeatChannels  predicate identifying channels that use looping repeats
	 * @param tempo           supplier of the current tempo
	 * @param measureDuration supplier of the current measure duration in seconds
	 * @param length          section length in measures
	 * @param sampleRate      audio sample rate
	 */
	public DefaultChannelSectionFactory(ProjectedChromosome chromosome, int channels,
										IntPredicate wetChannels, IntPredicate repeatChannels,
										Supplier<Frequency> tempo, DoubleSupplier measureDuration,
										int length, int sampleRate) {
		this.clock = new TimeCell();
		this.duration = new PackedCollection(1);

		this.channels = channels;
		this.wetChannels = wetChannels;
		this.repeatChannels = repeatChannels;
		this.tempo = tempo;
		this.measureDuration = measureDuration;
		this.length = length;
		this.sampleRate = sampleRate;

		this.volume = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene gene = chromosome.addGene(3);
			gene.setRange(0, 0.1, 0.9);
			gene.setRange(1, 0.4, 0.8);
			gene.setRange(2, 0.0, 0.9);
			return gene;
		}).collect(Collectors.toList()));

		this.volumeExp = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(1);
			g.setTransform(p -> oneToInfinity(p, 1.0).multiply(c(10.0)));
			g.setRange(0, factorForExponent(0.9), factorForExponent(1.2));
			return g;
		}).collect(Collectors.toList()));

		this.lowPassFilter = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene gene = chromosome.addGene(3);
			gene.setRange(0, 0.1, 0.9);
			gene.setRange(1, 0.4, 0.8);
			gene.setRange(2, 0.0, 0.75);
			return gene;
		}).collect(Collectors.toList()));

		this.lowPassFilterExp = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(1);
			g.setTransform(p -> oneToInfinity(p, 1.0).multiply(c(10.0)));
			g.setRange(0, factorForExponent(0.9), factorForExponent(2.5));
			return g;
		}).collect(Collectors.toList()));

		PackedCollection repeat = new PackedCollection(repeatChoices.length);
		repeat.setMem(Arrays.stream(repeatChoices).map(this::factorForRepeat).toArray());

		this.simpleDuration = chromosome(IntStream.range(0, channels)
				.mapToObj(i -> chromosome.addChoiceGene(repeat,1))
				.collect(Collectors.toList()));

		this.simpleDurationSpeedUp = chromosome(IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(2);
			g.setTransform(p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			g.setRange(0, factorForRepeatSpeedUpDuration(1), factorForRepeatSpeedUpDuration(4));
			g.setRange(1, factorForRepeatSpeedUpDuration(16), factorForRepeatSpeedUpDuration(80));
			return g;
		}).collect(Collectors.toList()));
	}

	/**
	 * Creates the next channel section at the given measure position.
	 *
	 * @param position the starting measure position of the section
	 * @return the created channel section
	 * @throws IllegalArgumentException if all channels have already been created
	 */
	public Section createSection(int position) {
		if (channel >= channels)
			throw new IllegalArgumentException();
		return new Section(position, length, channel++,
				(int) (sampleRate * length * measureDuration.getAsDouble()));
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList();
		setup.add(() -> () -> duration.setMem(length * measureDuration.getAsDouble()));
		return setup;
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();

		if (clock != null) clock.destroy();
		if (duration != null) duration.destroy();
		clock = null;
		duration = null;
	}

	@Override
	public Console console() { return CellFeatures.console; }

	/**
	 * A channel section produced by this factory. Processes audio by applying
	 * volume and filter envelopes and optional sample-repeat scheduling.
	 */
	public class Section implements ChannelSection {
		/** Starting measure position of this section. */
		private int position;

		/** Length of this section in measures. */
		private int length;

		/** Pattern channel index this section is responsible for. */
		private int channel;

		/** Total number of audio samples for this section's duration. */
		private int samples;

		/** Destroyable resources held by this section. */
		private List<Destroyable> dependencies;

		/** Creates an empty section; fields must be set before use. */
		public Section() { }

		/**
		 * Creates a section with the specified position, length, channel, and frame count.
		 *
		 * @param position the start position in measures
		 * @param length   the length in measures
		 * @param channel  the zero-based pattern channel index
		 * @param samples  total number of audio samples for this section
		 */
		protected Section(int position, int length,
						  int channel, int samples) {
			this.position = position;
			this.length = length;
			this.channel = channel;
			this.samples = samples;
			this.dependencies = new ArrayList<>();
		}

		@Override
		public int getPosition() { return position; }

		@Override
		public int getLength() { return length; }

		@Override
		public Supplier<Runnable> process(Producer<PackedCollection> destination, Producer<PackedCollection> source) {
			PackedCollection input = new PackedCollection(samples);
			PackedCollection output = new PackedCollection(shape(1, samples)).traverse(1);
			dependencies.add(input);
			dependencies.add(output);

			int repeatGene = channel; // 0;
			Producer<PackedCollection> r = simpleDuration.valueAt(repeatGene, 0)
													.getResultant(c(tempo.get().l(1)));
			Producer<PackedCollection> su = simpleDurationSpeedUp.valueAt(repeatGene, 0)
													.getResultant(c(1.0));
			Producer<PackedCollection> so = simpleDurationSpeedUp.valueAt(repeatGene, 1)
													.getResultant(c(1.0));
			Producer<PackedCollection> repeat = durationAdjustment(r, su, so, clock.time(sampleRate));

			CellList cells = cells(1, i ->
					new WaveCell(input.traverseEach(), sampleRate, 1.0, c(0.0), repeatChannels.test(channel) ? c(repeat) : null))
					.addRequirements(clock);

			if (enableVolumeRiseFall) {
				Producer<PackedCollection> d = volume.valueAt(channel, 0).getResultant(c(1.0));
				Producer<PackedCollection> m = volume.valueAt(channel, 1).getResultant(c(1.0));
				Producer<PackedCollection> p = volume.valueAt(channel, 2).getResultant(c(1.0));
				Producer<PackedCollection> e = volumeExp.valueAt(channel, 0).getResultant(c(1.0));

				Producer<PackedCollection> v = riseFall(0, 1.0, 0.0,
						d, m, p, e, clock.time(sampleRate), p(duration));

				cells = cells.map(fc(i -> volume(v)));
			}

			if (enableFilter && wetChannels.test(channel)) {
				Producer<PackedCollection> d = lowPassFilter.valueAt(channel, 0).getResultant(c(1.0));
				Producer<PackedCollection> m = lowPassFilter.valueAt(channel, 1).getResultant(c(1.0));
				Producer<PackedCollection> p = lowPassFilter.valueAt(channel, 2).getResultant(c(1.0));
				Producer<PackedCollection> e = lowPassFilterExp.valueAt(channel, 0).getResultant(c(1.0));

				Producer<PackedCollection> lpFreq = riseFall(0, MAX_FILTER_RISE, 0.0,
															d, m, p, e, clock.time(sampleRate), p(duration));
				Producer<PackedCollection> resonance = scalar(FixedFilterChromosome.defaultResonance);
				cells = cells.map(fc(i -> lp(lpFreq, resonance)));
			}

			dependencies.add(cells);

			OperationList process = new OperationList();
			process.add(new MemoryDataCopy("DefaultChannelSection Input", () -> source.get().evaluate(), () -> input, samples));
			process.add(cells.export(output));
			process.add(new MemoryDataCopy("DefaultChannelSection Output", () -> output, () -> destination.get().evaluate(), samples));
			return process;
		}

		@Override
		public void destroy() {
			ChannelSection.super.destroy();
			Destroyable.destroy(dependencies);
			dependencies.clear();
		}
	}
}
