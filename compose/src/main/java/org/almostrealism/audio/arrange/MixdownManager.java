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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.optimize.FixedFilterChromosome;
import org.almostrealism.audio.optimize.OptimizeFactorFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.AdjustableDelayCell;
import org.almostrealism.graph.PassThroughCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.ReceptorCell;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGene;
import org.almostrealism.heredity.TemporalFactor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MixdownManager implements Setup, Destroyable, CellFeatures, OptimizeFactorFeatures {
	public static final int mixdownDuration = 140;

	public static boolean enableMixdown = false;
	public static boolean enableSourcesOnly = false;
	public static boolean disableClean = false;

	public static boolean enableMainFilterUp = true;
	public static boolean enableAutomationManager = true;

	public static boolean enableEfxFilters = true;
	public static boolean enableEfx = true;
	public static boolean enableReverb = true;
	public static boolean enableTransmission = true;
	public static boolean enableWetInAdjustment = true;
	public static boolean enableMasterFilterDown = true;
	public static boolean enableRiser = false;

	public static boolean enableWetSources = true;

	protected static double reverbLevel = 2.0;

	private final AutomationManager automation;
	private final TimeCell clock;
	private final int sampleRate;

	private final PackedCollection volumeAdjustmentScale;
	private final PackedCollection mainFilterUpAdjustmentScale;
	private final PackedCollection mainFilterDownAdjustmentScale;
	private final PackedCollection reverbAdjustmentScale;

	private final Chromosome<PackedCollection> volumeSimple;
	private final Chromosome<PackedCollection> mainFilterUpSimple;
	private final Chromosome<PackedCollection> wetInSimple;

	private final Chromosome<PackedCollection> transmission;
	private final Chromosome<PackedCollection> wetOut;
	private final Chromosome<PackedCollection> delay;

	private final Chromosome<PackedCollection> delayDynamicsSimple;

	private final Chromosome<PackedCollection> reverb;
	private final Chromosome<PackedCollection> reverbAutomation;
	private final FixedFilterChromosome wetFilter;
	private final Chromosome<PackedCollection> mainFilterDownSimple;

	private List<Integer> reverbChannels;

	private final List<Destroyable> dependencies;

	public MixdownManager(ProjectedChromosome chromosome,
						  int channels, int delayLayers,
						  AutomationManager automation,
						  TimeCell clock, int sampleRate) {
		this.automation = automation;
		this.clock = clock;
		this.sampleRate = sampleRate;

		this.volumeAdjustmentScale = new PackedCollection(1).fill(1.0);
		this.mainFilterUpAdjustmentScale = new PackedCollection(1).fill(1.0);
		this.mainFilterDownAdjustmentScale = new PackedCollection(1).fill(1.0);
		this.reverbAdjustmentScale = new PackedCollection(1).fill(1.0);

		this.volumeSimple = chromosome(initializeAdjustment(channels, chromosome));
		this.mainFilterUpSimple = chromosome(IntStream.range(0, channels)
				.mapToObj(i -> chromosome.addGene(AutomationManager.GENE_LENGTH))
				.collect(Collectors.toList()));

		this.wetInSimple = chromosome(initializeAdjustment(channels, chromosome));

		this.transmission = chromosome(IntStream.range(0, delayLayers)
				.mapToObj(i -> chromosome.addGene(delayLayers))
				.collect(Collectors.toList()));

		this.wetOut = chromosome(List.of(chromosome.addGene(delayLayers)));

		this.delay = chromosome(IntStream.range(0, delayLayers).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(1);
			g.setTransform(p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			return g;
		}).collect(Collectors.toList()));

		this.delayDynamicsSimple = chromosome(initializePolycyclic(delayLayers, chromosome));

		this.reverb = chromosome(IntStream.range(0, channels)
				.mapToObj(i -> chromosome.addGene(1))
				.collect(Collectors.toList()));

		this.reverbAutomation = chromosome(IntStream.range(0, channels)
				.mapToObj(i -> chromosome.addGene(AutomationManager.GENE_LENGTH))
				.collect(Collectors.toList()));

		Chromosome<PackedCollection> wf = chromosome(IntStream.range(0, channels)
				.mapToObj(i -> chromosome.addGene(FixedFilterChromosome.SIZE))
				.collect(Collectors.toList()));
		this.wetFilter = new FixedFilterChromosome(wf, sampleRate);

		this.mainFilterDownSimple = chromosome(initializeAdjustment(channels, chromosome));

		initRanges(new Configuration(channels), delayLayers);

		this.reverbChannels = new ArrayList<>();
		this.dependencies = new ArrayList<>();
	}

	public AutomationManager getAutomationManager() { return automation; }

	public void setVolumeAdjustmentScale(double scale) {
		volumeAdjustmentScale.set(0, scale);
	}

	public void setMainFilterUpAdjustmentScale(double scale) {
		mainFilterUpAdjustmentScale.set(0, scale);
	}

	public void setMainFilterDownAdjustmentScale(double scale) {
		mainFilterDownAdjustmentScale.set(0, scale);
	}

	public void setReverbAdjustmentScale(double scale) {
		reverbAdjustmentScale.set(0, scale);
	}

	public List<Integer> getReverbChannels() {
		return reverbChannels;
	}

	public void setReverbChannels(List<Integer> reverbChannels) {
		this.reverbChannels = reverbChannels;
	}

	public void initRanges(Configuration config, int delayLayers) {
		volumeSimple.forEach(gene -> {
			((ProjectedGene) gene).setRange(0,
					factorForPeriodicAdjustmentDuration(config.periodicVolumeDurationMin),
					factorForPeriodicAdjustmentDuration(config.periodicVolumeDurationMax));
			((ProjectedGene) gene).setRange(1,
					factorForPolyAdjustmentDuration(config.overallVolumeDurationMin),
					factorForPolyAdjustmentDuration(config.overallVolumeDurationMax));
			((ProjectedGene) gene).setRange(2,
					factorForPolyAdjustmentExponent(config.overallVolumeExponentMin),
					factorForPolyAdjustmentExponent(config.overallVolumeExponentMax));
			((ProjectedGene) gene).setRange(3,
					factorForAdjustmentInitial(config.minVolumeValue),
					factorForAdjustmentInitial(config.maxVolumeValue));
			((ProjectedGene) gene).setRange(4, -1.0, -1.0);
			((ProjectedGene) gene).setRange(5,
					factorForAdjustmentOffset(config.overallVolumeOffsetMin),
					factorForAdjustmentOffset(config.overallVolumeOffsetMax));
		});

		wetInSimple.forEach(gene -> {
			((ProjectedGene) gene).setRange(0,
					factorForPeriodicAdjustmentDuration(config.periodicWetInDurationMin),
					factorForPeriodicAdjustmentDuration(config.periodicWetInDurationMax));
			((ProjectedGene) gene).setRange(1,
					factorForPolyAdjustmentDuration(config.overallWetInDurationMin),
					factorForPolyAdjustmentDuration(config.overallWetInDurationMax));
			((ProjectedGene) gene).setRange(2,
					factorForPolyAdjustmentExponent(config.overallWetInExponentMin),
					factorForPolyAdjustmentExponent(config.overallWetInExponentMax));
			((ProjectedGene) gene).setRange(3,
					factorForAdjustmentInitial(0),
					factorForAdjustmentInitial(0));
			((ProjectedGene) gene).setRange(4, 1.0, 1.0);
			((ProjectedGene) gene).setRange(5,
					factorForAdjustmentOffset(config.overallWetInOffsetMin),
					factorForAdjustmentOffset(config.overallWetInOffsetMax));
		});

		transmission.forEach(gene -> {
			ProjectedGene pg = (ProjectedGene) gene;
			IntStream.range(0, delayLayers).forEach(i -> pg.setRange(i,
					config.minTransmission, config.maxTransmission));
		});

		wetOut.forEach(gene -> {
			ProjectedGene pg = (ProjectedGene) gene;
			IntStream.range(0, delayLayers).forEach(i ->
					pg.setRange(i, config.minWetOut, config.maxWetOut));
		});

		delay.forEach(gene -> {
			ProjectedGene pg = (ProjectedGene) gene;
			pg.setRange(0,
					factorForDelay(config.minDelay),
					factorForDelay(config.maxDelay));
		});

		delayDynamicsSimple.forEach(gene -> {
			ProjectedGene pg = (ProjectedGene) gene;
			pg.setRange(0,
					factorForSpeedUpDuration(config.periodicSpeedUpDurationMin),
					factorForSpeedUpDuration(config.periodicSpeedUpDurationMax));
			pg.setRange(1,
					factorForSpeedUpPercentage(config.periodicSpeedUpPercentageMin),
					factorForSpeedUpPercentage(config.periodicSpeedUpPercentageMax));
			pg.setRange(2,
					factorForSlowDownDuration(config.periodicSlowDownDurationMin),
					factorForSlowDownDuration(config.periodicSlowDownDurationMax));
			pg.setRange(3,
					factorForSlowDownPercentage(config.periodicSlowDownPercentageMin),
					factorForSlowDownPercentage(config.periodicSlowDownPercentageMax));
			pg.setRange(4,
					factorForPolySpeedUpDuration(config.overallSpeedUpDurationMin),
					factorForPolySpeedUpDuration(config.overallSpeedUpDurationMax));
			pg.setRange(5,
					factorForPolySpeedUpExponent(config.overallSpeedUpExponentMin),
					factorForPolySpeedUpExponent(config.overallSpeedUpExponentMax));
		});

		reverb.forEach(gene -> {
			((ProjectedGene) gene).setRange(0, 0.0, 1.0);
		});

		wetFilter.setHighPassRange(config.minHighPass, config.maxHighPass);
		wetFilter.setLowPassRange(config.minLowPass, config.maxLowPass);

		mainFilterDownSimple.forEach(gene -> {
			((ProjectedGene) gene).setRange(0,
					factorForPeriodicAdjustmentDuration(config.periodicMasterFilterDownDurationMin),
					factorForPeriodicAdjustmentDuration(config.periodicMasterFilterDownDurationMax));
			((ProjectedGene) gene).setRange(1,
					factorForPolyAdjustmentDuration(config.overallMasterFilterDownDurationMin),
					factorForPolyAdjustmentDuration(config.overallMasterFilterDownDurationMax));
			((ProjectedGene) gene).setRange(2,
					factorForPolyAdjustmentExponent(config.overallMasterFilterDownExponentMin),
					factorForPolyAdjustmentExponent(config.overallMasterFilterDownExponentMax));
			((ProjectedGene) gene).setRange(3,
					factorForAdjustmentInitial(1.0),
					factorForAdjustmentInitial(1.0));
			((ProjectedGene) gene).setRange(4, -1.0, -1.0);
			((ProjectedGene) gene).setRange(5,
					factorForAdjustmentOffset(config.overallMasterFilterDownOffsetMin),
					factorForAdjustmentOffset(config.overallMasterFilterDownOffsetMax));
		});
	}

	@Override
	public Supplier<Runnable> setup() {
		return new OperationList("Mixdown Manager Setup");
	}

	public CellList cells(CellList sources,
						  MultiChannelAudioOutput output,
						  ChannelInfo.StereoChannel audioChannel) {
		return cells(sources, null, null, output, audioChannel, i -> i);
	}

	public CellList cells(CellList sources, CellList wetSources, CellList riser,
						  MultiChannelAudioOutput output,
						  ChannelInfo.StereoChannel audioChannel,
						  IntUnaryOperator channelIndex) {
		CellList cells = createCells(sources, wetSources, riser,
							output, audioChannel, channelIndex);
		dependencies.add(cells);
		return cells;
	}

	protected CellList createCells(CellList sources, CellList wetSources, CellList riser,
								   MultiChannelAudioOutput output,
								   ChannelInfo.StereoChannel audioChannel,
								   IntUnaryOperator channelIndex) {
		CellList cells = sources;

		if (enableMainFilterUp) {
			// Apply dynamic high pass filters
			if (enableAutomationManager) {
				cells = cells.map(fc(i -> {
					Producer<PackedCollection> v =
							automation.getAggregatedValue(
									mainFilterUpSimple.valueAt(channelIndex.applyAsInt(i)),
									p(mainFilterUpAdjustmentScale), -40.0);
					return hp(scalar(20000).multiply(v), scalar(FixedFilterChromosome.defaultResonance));
				}));
			} else {
				cells = cells.map(fc(i -> {
					Factor<PackedCollection> f = toAdjustmentGene(clock, sampleRate,
							p(mainFilterUpAdjustmentScale), mainFilterUpSimple,
							channelIndex.applyAsInt(i)).valueAt(0);
					return hp(scalar(20000).multiply(f.getResultant(c(1.0))), scalar(FixedFilterChromosome.defaultResonance));
				}));
			}
		}

		IntFunction<Factor<PackedCollection>> v = i -> factor(toAdjustmentGene(clock, sampleRate,
														p(volumeAdjustmentScale), volumeSimple,
														channelIndex.applyAsInt(i)).valueAt(0));

		if (enableSourcesOnly) {
			List<Receptor<PackedCollection>> r = new ArrayList<>();
			r.add(output.getMaster(audioChannel));
			r.addAll(output.getMeasures(audioChannel));

			return cells
					.map(fc(v))
					.sum().map(fc(i -> sf(0.8))).map(i -> new ReceptorCell<>(Receptor.to(r.stream())));
		}

		if (enableMixdown)
			cells = cells.mixdown(mixdownDuration);

		boolean reverbActive = enableReverb && !getReverbChannels().isEmpty() &&
			IntStream.range(0, sources.size())
					.map(i -> channelIndex.applyAsInt(i))
					.anyMatch(getReverbChannels()::contains);

		IntFunction<Factor<PackedCollection>> reverbFactor;

		if (!reverbActive) {
			reverbFactor = i -> sf(0.0);
		} else if (enableAutomationManager) {
			reverbFactor = i -> getReverbChannels().contains(channelIndex.applyAsInt(i)) ?
					in -> multiply(in, automation.getAggregatedValue(
								reverbAutomation.valueAt(channelIndex.applyAsInt(i)),
								p(reverbAdjustmentScale), 0.0))
							.multiply(c(reverbLevel)) :
						sf(0.0);
		} else {
			reverbFactor = i -> getReverbChannels().contains(channelIndex.applyAsInt(i)) ?
					factor(reverb.valueAt(channelIndex.applyAsInt(i), 0)) :
						sf(0.0);
		}

		CellList main;
		CellList efx;
		CellList reverb;

		if (enableWetSources) {
			// Apply volume to main
			main = cells.map(fc(v));

			// Branch from wet sources for efx and reverb
			CellList[] branch = wetSources.branch(
					enableEfxFilters ?
							fc(i -> v.apply(i).andThen(wetFilter.valueAt(channelIndex.applyAsInt(i), 0))) :
							fc(v),
					fc(reverbFactor));

			efx = branch[0];
			reverb = branch[1];
		} else {
			// Branch from main
			CellList[] branch = cells.branch(
					fc(v),
					enableEfxFilters ?
							fc(i -> v.apply(i).andThen(wetFilter.valueAt(channelIndex.applyAsInt(i), 0))) :
							fc(v),
					fc(reverbFactor));

			main = branch[0];
			efx = branch[1];
			reverb = branch[2];
		}

		if (output.isStemsActive()) {
			main = main.branch(i -> new ReceptorCell<>(output.getStem(i, audioChannel)),
								i -> new PassThroughCell<>())[1];
		}

		// Sum the main layer
		main = main.sum();

		if (enableEfx) {
			main = createEfx(main, efx, reverbActive ? reverb : null, riser,
					sources.size(), output, audioChannel, channelIndex);
		} else if (output.isMeasuresActive()) {
			// Deliver main to the output and measure for MAIN and WET
			main = main.map(i -> new ReceptorCell<>(Receptor.to(
					output.getMaster(audioChannel),
					output.getMeasure(ChannelInfo.Voicing.MAIN, audioChannel),
					output.getMeasure(ChannelInfo.Voicing.WET, audioChannel))));
		} else {
			// Deliver main to the output
			main = main.map(i -> new ReceptorCell<>(output.getMaster(audioChannel)));
		}

		return main;
	}

	public CellList createEfx(CellList main, CellList efx, CellList reverb,
							  CellList riser, int sourceCount,
							  MultiChannelAudioOutput output,
							  ChannelInfo.StereoChannel audioChannel,
							  IntUnaryOperator channelIndex) {
		if (enableTransmission) {
			int delayLayers = delay.length();

			IntFunction<Factor<PackedCollection>> df =
					i -> toPolycyclicGene(clock, sampleRate, delayDynamicsSimple, i).valueAt(0);

			CellList delays = IntStream.range(0, delayLayers)
					.mapToObj(i -> new AdjustableDelayCell(OutputLine.sampleRate,
							delay.valueAt(i, 0).getResultant(c(1.0)),
							df.apply(i).getResultant(c(1.0))))
					.collect(CellList.collector());

			IntFunction<Gene<PackedCollection>> tg =
					i -> delayGene(delayLayers, toAdjustmentGene(clock, sampleRate, null, wetInSimple, channelIndex.applyAsInt(i)));

			// Route each line to each delay layer
			efx = efx.m(fi(), delays, tg)
					// Feedback grid
					.mself(fi(), transmission, fc(wetOut.valueAt(0)))
					.sum();
		} else {
			efx = efx.sum();
		}

		if (reverb != null) {
			// Combine inputs and apply reverb
			reverb = reverb.sum().map(fc(i -> new DelayNetwork(sampleRate, false)));

			if (enableTransmission) {
				// Combine reverb with efx
				efx = cells(efx, reverb).sum();
			} else {
				// There are no other fx
				efx = reverb;
			}
		}

		if (disableClean) {
			List<Receptor<PackedCollection>> measures = output.getMeasures(audioChannel);

			Receptor[] r = new Receptor[measures.size() + 1];
			r[0] = output.getMaster(audioChannel);
			for (int i = 0; i < measures.size(); i++) r[i + 1] = measures.get(i);

			efx.get(0).setReceptor(Receptor.to(r));
			return efx;
		}

		List<Receptor<PackedCollection>> efxReceptors = new ArrayList<>();
		efxReceptors.add(main.get(0));
		if (output.isMeasuresActive()) {
			efxReceptors.add(output.getMeasure(ChannelInfo.Voicing.WET, audioChannel));
		}
		if (output.isStemsActive()) {
			efxReceptors.add(output.getStem(sourceCount, audioChannel));
		}

		efx.get(0).setReceptor(Receptor.to(efxReceptors.toArray(Receptor[]::new)));

		if (enableMasterFilterDown) {
			// Apply dynamic low pass filter
			main = main.map(fc(i -> {
				Factor<PackedCollection> f = toAdjustmentGene(clock, sampleRate,
						p(mainFilterDownAdjustmentScale), mainFilterDownSimple,
						channelIndex.applyAsInt(i)).valueAt(0);
				return lp(scalar(20000).multiply(f.getResultant(c(1.0))), scalar(FixedFilterChromosome.defaultResonance));
			}));
		}

		// TODO  Riser should actually feed into effects, if they are active
		if (enableRiser) {
			main = cells(main, riser).sum();
		}

		// Deliver main to the output and measure #1
		if (!output.isMeasuresActive()) {
			main = main.map(i -> new ReceptorCell<>(output.getMaster(audioChannel)));
		} else {
			main = main.map(i -> new ReceptorCell<>(Receptor.to(
					output.getMaster(audioChannel),
					output.getMeasure(ChannelInfo.Voicing.MAIN, audioChannel))));
		}

		return cells(main, efx);
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		Destroyable.destroy(dependencies);
		dependencies.clear();
	}

	/**
	 * This method wraps the specified {@link Factor} to prevent it from
	 * being detected as Temporal by {@link org.almostrealism.graph.FilteredCell}s
	 * that would proceed to invoke the {@link org.almostrealism.time.Temporal#tick()} operation.
	 * This is not a good solution, and this process needs to be reworked, so
	 * it is clear who bears the responsibility for invoking {@link org.almostrealism.time.Temporal#tick()}
	 * and it doesn't get invoked multiple times.
	 */
	private TemporalFactor<PackedCollection> factor(Factor<PackedCollection> f) {
		return v -> f.getResultant(v);
	}

	/**
	 * Create a {@link Gene} for routing delays.
	 * The current implementation delivers audio to
	 * the first delay based on the wet level, and
	 * delivers nothing to the others.
	 */
	private Gene<PackedCollection> delayGene(int delays, Gene<PackedCollection> wet) {
		Factor<PackedCollection>[] gene = new Factor[delays];

		if (enableWetInAdjustment) {
			gene[0] = factor(wet.valueAt(0));
		} else {
			gene[0] = p -> c(0.2).multiply(p);
		}

		IntStream.range(1, delays).forEach(i -> gene[i] = (p -> c(0.0)));
		return g(gene);
	}

	public static class Configuration implements OptimizeFactorFeatures {
		public IntToDoubleFunction minChoice, maxChoice;
		public double minChoiceValue, maxChoiceValue;

		public double repeatSpeedUpDurationMin, repeatSpeedUpDurationMax;

		public IntToDoubleFunction minX, maxX;
		public IntToDoubleFunction minY, maxY;
		public IntToDoubleFunction minZ, maxZ;
		public double minXValue, maxXValue;
		public double minYValue, maxYValue;
		public double minZValue, maxZValue;

		public IntToDoubleFunction minVolume, maxVolume;
		public double minVolumeValue, maxVolumeValue;
		public double periodicVolumeDurationMin, periodicVolumeDurationMax;
		public double overallVolumeDurationMin, overallVolumeDurationMax;
		public double overallVolumeExponentMin, overallVolumeExponentMax;
		public double overallVolumeOffsetMin, overallVolumeOffsetMax;

		public double periodicFilterUpDurationMin, periodicFilterUpDurationMax;
		public double overallFilterUpDurationMin, overallFilterUpDurationMax;
		public double overallFilterUpExponentMin, overallFilterUpExponentMax;
		public double overallFilterUpOffsetMin, overallFilterUpOffsetMax;

		public double minTransmission, maxTransmission;
		public double minDelay, maxDelay;

		public double periodicSpeedUpDurationMin, periodicSpeedUpDurationMax;
		public double periodicSpeedUpPercentageMin, periodicSpeedUpPercentageMax;
		public double periodicSlowDownDurationMin, periodicSlowDownDurationMax;
		public double periodicSlowDownPercentageMin, periodicSlowDownPercentageMax;
		public double overallSpeedUpDurationMin, overallSpeedUpDurationMax;
		public double overallSpeedUpExponentMin, overallSpeedUpExponentMax;

		public double periodicWetInDurationMin, periodicWetInDurationMax;
		public double overallWetInDurationMin, overallWetInDurationMax;
		public double overallWetInExponentMin, overallWetInExponentMax;
		public double overallWetInOffsetMin, overallWetInOffsetMax;

		public double minWetOut, maxWetOut;
		public double minHighPass, maxHighPass;
		public double minLowPass, maxLowPass;

		public double periodicMasterFilterDownDurationMin, periodicMasterFilterDownDurationMax;
		public double overallMasterFilterDownDurationMin, overallMasterFilterDownDurationMax;
		public double overallMasterFilterDownExponentMin, overallMasterFilterDownExponentMax;
		public double overallMasterFilterDownOffsetMin, overallMasterFilterDownOffsetMax;

		public double[] offsetChoices;
		public double[] repeatChoices;

		public Configuration() { this(1); }

		public Configuration(int scale) {
			double offset = 30;
			double duration = 0;

			minChoiceValue = 0.0;
			maxChoiceValue = 1.0;
			repeatSpeedUpDurationMin = 5.0;
			repeatSpeedUpDurationMax = 60.0;

			minVolumeValue = 2.0 / scale;
			maxVolumeValue = 2.0 / scale;
			periodicVolumeDurationMin = 0.5;
			periodicVolumeDurationMax = 180;
//			overallVolumeDurationMin = 60;
//			overallVolumeDurationMax = 240;
			overallVolumeDurationMin = duration + 5.0;
			overallVolumeDurationMax = duration + 30.0;
			overallVolumeExponentMin = 1;
			overallVolumeExponentMax = 1;
			overallVolumeOffsetMin = offset + 25.0;
			overallVolumeOffsetMax = offset + 45.0;

			periodicFilterUpDurationMin = 0.5;
			periodicFilterUpDurationMax = 180;
			overallFilterUpDurationMin = duration + 90.0;
			overallFilterUpDurationMax = duration + 360.0;
			overallFilterUpExponentMin = 0.5;
			overallFilterUpExponentMax = 3.5;
			overallFilterUpOffsetMin = offset + 15.0;
			overallFilterUpOffsetMax = offset + 45.0;

			minTransmission = 0.3;
			maxTransmission = 0.6;
			minDelay = 4.0;
			maxDelay = 20.0;

			periodicSpeedUpDurationMin = 20.0;
			periodicSpeedUpDurationMax = 180.0;
			periodicSpeedUpPercentageMin = 0.0;
			periodicSpeedUpPercentageMax = 2.0;

			periodicSlowDownDurationMin = 20.0;
			periodicSlowDownDurationMax = 180.0;
			periodicSlowDownPercentageMin = 0.0;
			periodicSlowDownPercentageMax = 0.9;

			overallSpeedUpDurationMin = 10.0;
			overallSpeedUpDurationMax = 60.0;
			overallSpeedUpExponentMin = 1;
			overallSpeedUpExponentMax = 1;

			periodicWetInDurationMin = 0.5;
			periodicWetInDurationMax = 180;
			overallWetInDurationMin = duration + 5.0;
			overallWetInDurationMax = duration + 50.0;
			overallWetInExponentMin = 0.5;
			overallWetInExponentMax = 2.5;
			overallWetInOffsetMin = offset;
			overallWetInOffsetMax = offset + 40;

			minWetOut = 0.5;
			maxWetOut = 1.4;
			minHighPass = 0.0;
			maxHighPass = 5000.0;
			minLowPass = 15000.0;
			maxLowPass = 20000.0;

			periodicMasterFilterDownDurationMin = 0.5;
			periodicMasterFilterDownDurationMax = 90;
			overallMasterFilterDownDurationMin = duration + 30;
			overallMasterFilterDownDurationMax = duration + 120;
			overallMasterFilterDownExponentMin = 0.5;
			overallMasterFilterDownExponentMax = 3.5;
			overallMasterFilterDownOffsetMin = offset;
			overallMasterFilterDownOffsetMax = offset + 30;

			offsetChoices = IntStream.range(0, 7)
					.mapToDouble(i -> Math.pow(2, -i))
					.toArray();
			offsetChoices[0] = 0.0;

			repeatChoices = IntStream.range(0, 9)
					.map(i -> i - 2)
					.mapToDouble(i -> Math.pow(2, i))
					.toArray();

			repeatChoices = new double[] { 16 };


			minChoice = i -> minChoiceValue;
			maxChoice = i -> maxChoiceValue;
			minX = i -> minXValue;
			maxX = i -> maxXValue;
			minY = i -> minYValue;
			maxY = i -> maxYValue;
			minZ = i -> minZValue;
			maxZ = i -> maxZValue;
			minVolume = i -> minVolumeValue;
			maxVolume = i -> maxVolumeValue;
		}
	}
}
