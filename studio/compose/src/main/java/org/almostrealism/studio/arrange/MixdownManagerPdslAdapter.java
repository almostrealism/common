/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.arrange;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.model.Block;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.studio.optimize.OptimizeFactorFeatures;
import org.almostrealism.util.FirFilterTestFeatures;

import java.util.HashMap;
import java.util.Map;

/**
 * Boundary-layer glue between {@link MixdownManager}'s genome state and the
 * argument map consumed by the PDSL {@code mixdown_master} layer in
 * {@code engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl}.
 *
 * <p>{@link #buildArgsMap(MixdownManager, Config)} returns a populated
 * {@code Map<String, Object>} suitable for
 * {@code PdslLoader.buildLayer(program, "mixdown_master", policy, argsMap)}.
 * Every parameter the PDSL layer declares is sourced parameter-by-parameter
 * from the manager's chromosomes, mirroring the per-line gene reads in
 * {@link MixdownManager#createCells} and {@link MixdownManager#createEfx} —
 * each producer/weight choice is documented with the originating Java line.</p>
 *
 * <p>{@link #wrapBlockAsCellList(Block)} is a minimal bridge that exposes a
 * compiled PDSL {@link Block}'s forward {@link Cell} as a single-element
 * {@link CellList}. AudioScene consumes a CellList from
 * {@link MixdownManager#cells(CellList,
 * org.almostrealism.studio.health.MultiChannelAudioOutput,
 * org.almostrealism.music.data.ChannelInfo.StereoChannel)} — this helper
 * makes the same shape available from the Block side without changing
 * AudioScene. CellList is being deprecated long-term in favour of Block;
 * the helper is intentionally narrow and does not invent abstractions.</p>
 */
public class MixdownManagerPdslAdapter implements CellFeatures, OptimizeFactorFeatures,
		FirFilterTestFeatures {

	/** Singleton used internally to access the {@code OptimizeFactorFeatures} default methods. */
	private static final MixdownManagerPdslAdapter ADAPTER = new MixdownManagerPdslAdapter();

	/** Disallow external instantiation — the class is only used through static helpers. */
	private MixdownManagerPdslAdapter() {}

	/**
	 * Structural configuration consumed by {@link #buildArgsMap(MixdownManager, Config)}.
	 *
	 * <p>{@code channels} and {@code signalSize} mirror the PDSL layer's
	 * structural parameters; {@code firTaps} and {@code filterOrder} satisfy
	 * the FIR-coefficient declarations on {@code wet_filter_coeffs} and the
	 * scalar parameters on {@code highpass}/{@code lowpass}. {@code wetLevel}
	 * and {@code delaySamples} match the corresponding PDSL parameter names.</p>
	 */
	public static class Config {
		/** Number of audio channels. */
		public final int channels;
		/** Samples per channel per forward pass. */
		public final int signalSize;
		/** Audio sample rate in Hz. */
		public final int sampleRate;
		/** FIR filter order (taps = order + 1). */
		public final int filterOrder;
		/** Static wet-bus level (PDSL {@code wet_level} scalar). */
		public final double wetLevel;
		/** Static delay length in samples (PDSL {@code delay_samples} producer; rendered as a constant). */
		public final int delaySamples;

		/**
		 * Creates a configuration record.
		 *
		 * @param channels     audio channel count
		 * @param signalSize   samples per pass
		 * @param sampleRate   audio sample rate (Hz)
		 * @param filterOrder  FIR filter order
		 * @param wetLevel     wet-send scalar level
		 * @param delaySamples static integer delay length in samples
		 */
		public Config(int channels, int signalSize, int sampleRate, int filterOrder,
					  double wetLevel, int delaySamples) {
			this.channels = channels;
			this.signalSize = signalSize;
			this.sampleRate = sampleRate;
			this.filterOrder = filterOrder;
			this.wetLevel = wetLevel;
			this.delaySamples = delaySamples;
		}
	}

	/**
	 * Builds the argument map for the {@code mixdown_master} PDSL layer from
	 * a constructed {@link MixdownManager}.
	 *
	 * <p>Producer-typed parameters are sampled from channel 0's gene state.
	 * The PDSL layer applies the same producer to every channel inside
	 * {@code for each channel}, so this is necessarily an approximation of
	 * the per-channel-distinct envelopes in {@code MixdownManager.createCells()};
	 * the structural rendition discussion in {@code PDSL_AUDIO_DSP.md}
	 * Section 11 describes this trade-off.</p>
	 *
	 * @param manager constructed mixdown manager (chromosomes already populated)
	 * @param config  structural configuration (channels, signal size, sample rate, FIR order, wet level, delay)
	 * @return populated argument map for {@code PdslLoader.buildLayer(...)}
	 */
	public static Map<String, Object> buildArgsMap(MixdownManager manager, Config config) {
		Map<String, Object> args = new HashMap<>();
		args.put("channels", config.channels);
		args.put("signal_size", config.signalSize);
		args.put("fir_taps", config.filterOrder + 1);
		args.put("sample_rate", (double) config.sampleRate);
		args.put("filter_order", (double) config.filterOrder);
		args.put("wet_level", config.wetLevel);
		args.put("delay_samples", config.delaySamples);

		// hp_cutoff: producer([1])
		// Mirrors MixdownManager.createCells() line 519-523:
		//   v = automation.getAggregatedValue(mainFilterUpSimple.valueAt(channel),
		//                                     p(mainFilterUpAdjustmentScale), -40.0)
		//   cutoff_hz = scalar(20000) * v
		args.put("hp_cutoff", hpCutoffProducer(manager, 0));

		// volume: producer([1])
		// Mirrors MixdownManager.createCells() line 535-537:
		//   factor(toAdjustmentGene(clock, sampleRate, p(volumeAdjustmentScale),
		//                            volumeSimple, channel).valueAt(0))
		// The factor multiplies its input by f.getResultant(c(1.0)), so the volume
		// multiplier producer is exactly that resultant.
		args.put("volume", volumeProducer(manager, 0));

		// lp_cutoff: producer([1])
		// Mirrors MixdownManager.createEfx() line 720-725:
		//   f = toAdjustmentGene(clock, sampleRate, p(mainFilterDownAdjustmentScale),
		//                        mainFilterDownSimple, channel).valueAt(0)
		//   cutoff_hz = scalar(20000) * f.getResultant(c(1.0))
		args.put("lp_cutoff", lpCutoffProducer(manager, 0));

		// wet_filter_coeffs: producer([channels, fir_taps])
		// Mirrors MixdownManager.createCells() line 583-587 / 605-607 — the
		// FixedFilterChromosome at line 280-283 supplies dynamic IIR filters in
		// the Java path; the PDSL path renders these as static FIR coefficients
		// per channel by sampling the gene's HP/LP frequencies at args-build time.
		args.put("wet_filter_coeffs", wetFilterCoefficients(manager, config));

		// transmission: producer([channels, channels])
		// Mirrors MixdownManager.createEfx() line 677:
		//   .mself(fi(), transmission, fc(wetOut.valueAt(0)))
		// The transmission chromosome's gene[n].valueAt(m).getResultant(c(1.0))
		// supplies the matrix element at row n, column m. Sampled here as a
		// static [channels, channels] PackedCollection slot; the PDSL substrate
		// re-reads the slot every forward pass, so mutating it between renders
		// updates the routing without rebuilding the layer.
		args.put("transmission", transmissionMatrix(manager, config));

		// buffers, heads — fresh state slots, sized to match the PDSL subscript
		// convention (buffers indexed by channel via 'buffers[channel]' carves
		// signal_size samples per channel; heads indexed similarly carves 1 head
		// per channel). Mirrors MixdownManager.createEfx() line 665-669, where
		// each AdjustableDelayCell owns its own internal delay state.
		PackedCollection buffers = new PackedCollection(config.channels * config.signalSize);
		buffers.setMem(new double[config.channels * config.signalSize]);
		PackedCollection heads = new PackedCollection(config.channels);
		heads.setMem(new double[config.channels]);
		args.put("buffers", buffers);
		args.put("heads", heads);

		return args;
	}

	/**
	 * Produces a shape-{@code [1]} cutoff-frequency producer for the per-channel
	 * high-pass filter at the given channel.
	 *
	 * @param manager the mixdown manager whose chromosomes are read
	 * @param channel the source channel index (PDSL applies one producer to all channels)
	 * @return cutoff-frequency producer (Hz)
	 */
	private static Producer<PackedCollection> hpCutoffProducer(MixdownManager manager, int channel) {
		Producer<PackedCollection> v = manager.getAutomationManager().getAggregatedValue(
				manager.getMainFilterUpSimple().valueAt(channel),
				ADAPTER.p(manager.getMainFilterUpAdjustmentScale()), -40.0);
		return ADAPTER.multiply(ADAPTER.c(20000.0), v);
	}

	/**
	 * Produces a shape-{@code [1]} cutoff-frequency producer for the master
	 * low-pass filter at the given channel.
	 *
	 * @param manager the mixdown manager whose chromosomes are read
	 * @param channel the source channel index
	 * @return cutoff-frequency producer (Hz)
	 */
	private static Producer<PackedCollection> lpCutoffProducer(MixdownManager manager, int channel) {
		Producer<PackedCollection> v = ADAPTER.toAdjustmentGene(
						manager.getClock(), manager.getSampleRate(),
						ADAPTER.p(manager.getMainFilterDownAdjustmentScale()),
						manager.getMainFilterDownSimple(), channel)
				.valueAt(0).getResultant(ADAPTER.c(1.0));
		return ADAPTER.multiply(ADAPTER.c(20000.0), v);
	}

	/**
	 * Produces a shape-{@code [1]} per-channel volume multiplier producer.
	 *
	 * @param manager the mixdown manager whose chromosomes are read
	 * @param channel the source channel index
	 * @return volume multiplier producer
	 */
	private static Producer<PackedCollection> volumeProducer(MixdownManager manager, int channel) {
		return ADAPTER.toAdjustmentGene(
						manager.getClock(), manager.getSampleRate(),
						ADAPTER.p(manager.getVolumeAdjustmentScale()),
						manager.getVolumeSimple(), channel)
				.valueAt(0).getResultant(ADAPTER.c(1.0));
	}

	/**
	 * Builds a static {@code [channels, fir_taps]} per-channel low-pass FIR
	 * coefficient bank for the PDSL {@code wet_filter_coeffs} parameter. Each
	 * channel's row is computed from the wet-filter chromosome's low-pass
	 * frequency (gene slot 1) at gene-evaluation time.
	 *
	 * <p>The Java path's wet filter is an {@link org.almostrealism.audio.filter.AudioPassFilter}
	 * IIR chain (see {@link FixedFilterChromosome.FixedFilterGene#valueAt}); the
	 * PDSL {@code fir} primitive consumes static FIR coefficients. This is the
	 * structural mismatch the planning document calls out — the test in
	 * {@code MixdownManagerPdslVerificationTest} compares the two with that
	 * caveat documented.</p>
	 *
	 * @param manager the mixdown manager whose wet-filter chromosome is sampled
	 * @param config  structural configuration
	 * @return shape-{@code [channels, fir_taps]} coefficient slot
	 */
	private static PackedCollection wetFilterCoefficients(MixdownManager manager, Config config) {
		final int firTaps = config.filterOrder + 1;
		double[] flat = new double[config.channels * firTaps];
		FixedFilterChromosome wetFilter = manager.getWetFilter();
		for (int ch = 0; ch < config.channels; ch++) {
			double lpUnit = evaluateGeneFactor(wetFilter, ch, 1);
			double lpHz = Math.max(20.0, Math.min(lpUnit * 20000.0,
					0.49 * config.sampleRate));
			double[] coeffs = ADAPTER.referenceLowPassCoefficients(
					lpHz, config.sampleRate, config.filterOrder);
			System.arraycopy(coeffs, 0, flat, ch * firTaps, firTaps);
		}
		PackedCollection coeffs = new PackedCollection(new TraversalPolicy(config.channels, firTaps));
		coeffs.setMem(flat);
		return coeffs;
	}

	/**
	 * Builds a static {@code [channels, channels]} cross-channel transmission
	 * matrix slot from the wet-bus transmission chromosome. Mirrors the
	 * {@code mself(fi(), transmission, ...)} call at
	 * {@link MixdownManager#createEfx} line 677 by sampling each gene-pair value
	 * directly. The slot can be mutated between renders to update routing
	 * without rebuilding the PDSL layer (the producer-shape substrate re-reads
	 * the slot on every forward pass).
	 *
	 * @param manager the mixdown manager whose transmission chromosome is sampled
	 * @param config  structural configuration
	 * @return shape-{@code [channels, channels]} routing matrix slot
	 */
	private static PackedCollection transmissionMatrix(MixdownManager manager, Config config) {
		double[] data = new double[config.channels * config.channels];
		Chromosome<PackedCollection> chrom = manager.getTransmission();
		int rows = Math.min(chrom.length(), config.channels);
		for (int n = 0; n < rows; n++) {
			int cols = Math.min(chrom.valueAt(n).length(), config.channels);
			for (int m = 0; m < cols; m++) {
				data[n * config.channels + m] = evaluateGeneFactor(chrom, n, m);
			}
		}
		PackedCollection matrix = new PackedCollection(
				new TraversalPolicy(config.channels, config.channels));
		matrix.setMem(data);
		return matrix;
	}

	/**
	 * Evaluates a chromosome gene-factor's resultant at unit input, returning
	 * the resulting scalar. This is the boundary point where the genome's
	 * compiled producer is collapsed into a static {@code double} for use in
	 * coefficient/matrix slots — every other parameter retains its
	 * Producer form.
	 *
	 * @param chromosome the chromosome whose gene-factor is evaluated
	 * @param gene       gene index within the chromosome
	 * @param factor     factor index within the gene
	 * @return the scalar value of {@code chromosome.valueAt(gene).valueAt(factor).getResultant(c(1.0))}
	 */
	private static double evaluateGeneFactor(Chromosome<PackedCollection> chromosome,
											 int gene, int factor) {
		return chromosome.valueAt(gene).valueAt(factor)
				.getResultant(ADAPTER.c(1.0)).get().evaluate().toDouble(0);
	}

	/**
	 * Wraps a compiled PDSL {@link Block}'s forward {@link Cell} as a
	 * single-element {@link CellList}. This is the minimum bridge from the
	 * Block universe into the existing CellList-based render plumbing
	 * AudioScene consumes. Callers that need the multi-receptor delivery
	 * pattern of {@link MixdownManager#createEfx} (master + measures + stems)
	 * must wire those receptors onto the returned cell themselves via
	 * {@link Cell#setReceptor(org.almostrealism.graph.Receptor)}.
	 *
	 * @param block the compiled PDSL block whose forward cell is wrapped
	 * @return a single-element CellList containing {@code block.getForward()}
	 */
	public static CellList wrapBlockAsCellList(Block block) {
		CellList list = new CellList();
		list.add(block.getForward());
		return list;
	}
}
