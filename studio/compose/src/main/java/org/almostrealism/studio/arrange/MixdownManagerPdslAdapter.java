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
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.model.Block;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.studio.optimize.OptimizeFactorFeatures;
import org.almostrealism.util.FirFilterTestFeatures;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

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
 *
 * @deprecated This adapter is transitional. Once PDSL is the standard mixdown
 * path, the adapter disappears and {@link MixdownManager} itself becomes the
 * adapter — building its own argument map directly rather than routing through
 * this boundary-layer glue. The class exists only to keep the legacy
 * {@code MixdownManager} genome state and the PDSL {@code mixdown_master} layer
 * decoupled during the cutover.
 */
@Deprecated
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
		/** Master-bus headroom multiplier mirroring {@link MixdownManager#masterBusGain}. */
		public final double masterBusGain;

		/**
		 * Creates a configuration record using the project default master-bus gain.
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
			this(channels, signalSize, sampleRate, filterOrder, wetLevel, delaySamples,
					MixdownManager.masterBusGain);
		}

		/**
		 * Creates a configuration record with an explicit master-bus gain. The PDSL
		 * {@code mixdown_master} layer applies {@code scale(master_gain)} followed by
		 * a hard {@code clip(-1, 1)} as its master limiter stage, mirroring
		 * {@code MixdownManager.createEfx()}.
		 *
		 * @param channels      audio channel count
		 * @param signalSize    samples per pass
		 * @param sampleRate    audio sample rate (Hz)
		 * @param filterOrder   FIR filter order
		 * @param wetLevel      wet-send scalar level
		 * @param delaySamples  static integer delay length in samples
		 * @param masterBusGain master-bus headroom multiplier (use {@code 1.0} to disable
		 *                      the gain stage; the saturation stage still bounds peaks)
		 */
		public Config(int channels, int signalSize, int sampleRate, int filterOrder,
					  double wetLevel, int delaySamples, double masterBusGain) {
			this.channels = channels;
			this.signalSize = signalSize;
			this.sampleRate = sampleRate;
			this.filterOrder = filterOrder;
			this.wetLevel = wetLevel;
			this.delaySamples = delaySamples;
			this.masterBusGain = masterBusGain;
		}
	}

	/**
	 * Builds the argument map for the {@code mixdown_master} PDSL layer from
	 * a constructed {@link MixdownManager}.
	 *
	 * <p>The per-channel automation parameters (HP cutoff, volume) are supplied as
	 * shape-{@code [channels]} producers — one gene-driven producer per channel —
	 * which the PDSL layers read as {@code arg[channel]} inside {@code for each
	 * channel}, matching the per-channel-distinct envelopes in
	 * {@code MixdownManager.createCells()}. The master low-pass cutoff is a single
	 * post-sum stage and remains shape-{@code [1]}.</p>
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

		// hp_cutoff, volume: [channels] slots; lp_cutoff: [1] slot; hp_coeffs:
		// [channels, taps] and lp_coeffs: [taps] FIR coefficient slots. These are the
		// TIME-VARYING automation values (clock-driven sweeps and envelopes). They are
		// supplied as PackedCollection slots rather than producers because producer-valued
		// arguments — and any input-independent coefficient subgraph computed from them —
		// are evaluated when the model is built and frozen at their build-time values
		// inside the compiled graph; the audible symptom was the mainFilterUp sweep never
		// engaging in the PDSL render while the CellList swept. Collection slots are
		// re-read live every forward pass (the same contract the delay/feedback ring
		// state relies on); {@link #automationRefresh} re-evaluates the gene-driven
		// producers (including the full windowed-sinc coefficient computation for the
		// swept filters) into these slots once per buffer.
		args.put("hp_cutoff", new PackedCollection(config.channels).fill(0.0));
		args.put("volume", new PackedCollection(config.channels).fill(0.0));
		args.put("lp_cutoff", new PackedCollection(1).fill(0.0));
		int taps = config.filterOrder + 1;
		args.put("hp_coeffs", new PackedCollection(
				new TraversalPolicy(config.channels, taps)).fill(0.0));
		args.put("lp_coeffs", new PackedCollection(taps).fill(0.0));

		// wet_filter_coeffs: producer([channels, fir_taps])
		// Mirrors MixdownManager.createCells() — the
		// FixedFilterChromosome at supplies dynamic IIR filters in
		// the Java path; the PDSL path renders these as static FIR coefficients
		// per channel by sampling the gene's HP/LP frequencies at args-build time.
		args.put("wet_filter_coeffs", wetFilterCoefficients(manager, config));

		// transmission: producer([channels, channels])
		// Mirrors MixdownManager.createEfx():
		//   .mself(fi(), transmission, fc(wetOut.valueAt(0)))
		// The transmission chromosome's gene[n].valueAt(m).getResultant(c(1.0))
		// supplies the matrix element at row n, column m. Sampled here as a
		// static [channels, channels] PackedCollection slot; the PDSL substrate
		// re-reads the slot every forward pass, so mutating it between renders
		// updates the routing without rebuilding the layer.
		args.put("transmission", transmissionMatrix(manager, config));

		// master_gain: producer([1])
		// Mirrors MixdownManager.createEfx():
		//   if (masterBusGain != 1.0) main = main.map(... bound(in*gain, -1, 1) ...)
		// Sourced from Config.masterBusGain (defaults to MixdownManager.masterBusGain).
		// The PDSL master path also applies tanh_act() after this scale to bound the
		// output, replacing the Java path's hard clip with a soft saturator. The two
		// stages together replicate the master shaping the original Java pipeline
		// applies just before the output receptor.
		args.put("master_gain", config.masterBusGain);

		// buffers, heads — fresh state slots, sized to match the PDSL subscript
		// convention (buffers indexed by channel via 'buffers[channel]' carves
		// signal_size samples per channel; heads indexed similarly carves 1 head
		// per channel). Mirrors MixdownManager.createEfx(), where
		// each AdjustableDelayCell owns its own internal delay state.
		PackedCollection buffers = new PackedCollection(config.channels * config.signalSize);
		buffers.setMem(new double[config.channels * config.signalSize]);
		PackedCollection heads = new PackedCollection(config.channels);
		heads.setMem(new double[config.channels]);
		args.put("buffers", buffers);
		args.put("heads", heads);

		// Initialise the automation slots with their current (clock-position) values so
		// direct consumers of this map (tests, single-shot renders) see live gene values
		// even if they never run the per-buffer refresh.
		automationRefresh(manager, null, config, args).get().run();

		return args;
	}

	/**
	 * Builds the per-buffer refresh operation that re-evaluates every TIME-VARYING
	 * automation producer into its argument slot ({@code hp_cutoff}, {@code volume},
	 * {@code lp_cutoff}, and — when {@code efx} is provided — {@code efx_automation} and
	 * {@code reverb_send}). The real-time runner runs this once per buffer, before the
	 * model forward pass, so the compiled graph reads the automation values for the
	 * buffer's clock position. Producer-valued model arguments cannot be used for these:
	 * they are evaluated at model build time and frozen inside the compiled graph.
	 *
	 * @param manager constructed mixdown manager (chromosomes already populated)
	 * @param efx     constructed effects manager, or {@code null} when the efx-layer
	 *                slots are not present in {@code args}
	 * @param config  structural configuration
	 * @param args    the argument map previously built by {@code buildArgsMap}
	 * @return an operation assigning all time-varying slots from their producers
	 */
	public static Supplier<Runnable> automationRefresh(MixdownManager manager, EfxManager efx,
													   Config config, Map<String, Object> args) {
		OperationList refresh = new OperationList("PDSL Automation Refresh");
		PackedCollection hpCutoff = (PackedCollection) args.get("hp_cutoff");
		PackedCollection volume = (PackedCollection) args.get("volume");
		PackedCollection lpCutoff = (PackedCollection) args.get("lp_cutoff");
		PackedCollection hpCoeffs = (PackedCollection) args.get("hp_coeffs");
		PackedCollection lpCoeffs = (PackedCollection) args.get("lp_coeffs");
		int taps = config.filterOrder + 1;
		for (int ch = 0; ch < config.channels; ch++) {
			refresh.add(ADAPTER.a(1, ADAPTER.cp(hpCutoff.range(ADAPTER.shape(1), ch)),
					hpCutoffProducer(manager, ch)));
			refresh.add(ADAPTER.a(1, ADAPTER.cp(volume.range(ADAPTER.shape(1), ch)),
					volumeProducer(manager, ch)));
		}
		refresh.add(ADAPTER.a(1, ADAPTER.cp(lpCutoff), lpCutoffProducer(manager, 0)));

		// The FIR coefficient slots hold the TRUNCATED IMPULSE RESPONSE of Java's
		// AudioPassFilter biquad at the current cutoff. The IR recurrence cannot be built
		// as a producer graph (each unrolled step embeds copies of the two previous
		// subtrees — an exponential expression tree), so the responses are tabulated once
		// at build time over log-spaced cutoff bins and each refresh SELECTS a row with a
		// device-side gather: bin = round((bins-1) * ln(cutoff/10) / ln(20000/10)).
		PackedCollection hpTable = biquadResponseTable(true, config.sampleRate, taps);
		PackedCollection lpTable = biquadResponseTable(false, config.sampleRate, taps);
		double binScale = (FILTER_TABLE_BINS - 1)
				/ Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
		for (int ch = 0; ch < config.channels; ch++) {
			refresh.add(ADAPTER.a(taps,
					ADAPTER.cp(hpCoeffs.range(ADAPTER.shape(taps), ch * taps)),
					tableRow(hpTable, hpCutoffProducer(manager, ch), binScale, taps)));
		}
		refresh.add(ADAPTER.a(taps, ADAPTER.cp(lpCoeffs),
				tableRow(lpTable, lpCutoffProducer(manager, 0), binScale, taps)));

		if (efx != null) {
			PackedCollection efxAutomation = (PackedCollection) args.get("efx_automation");
			PackedCollection reverbSend = (PackedCollection) args.get("reverb_send");
			for (int ch = 0; ch < config.channels; ch++) {
				refresh.add(ADAPTER.a(1, ADAPTER.cp(efxAutomation.range(ADAPTER.shape(1), ch)),
						efxAutomationProducer(efx, ch)));
				refresh.add(ADAPTER.a(1, ADAPTER.cp(reverbSend.range(ADAPTER.shape(1), ch)),
						reverbSendProducer(manager, ch)));
			}
		}

		return refresh;
	}

	/**
	 * Builds the {@code [FILTER_TABLE_BINS, taps]} table of truncated biquad impulse
	 * responses over log-spaced cutoffs in {@code [MIN_FREQUENCY, 20000]} Hz. Computed
	 * once at argument-build time from closed-form coefficient math (no device reads);
	 * per-buffer refresh selects a row with a device-side gather.
	 *
	 * @param high       true for the high-pass design; false for low-pass
	 * @param sampleRate audio sample rate in Hz
	 * @param taps       FIR taps per response
	 * @return the response table
	 */
	private static PackedCollection biquadResponseTable(boolean high, int sampleRate, int taps) {
		PackedCollection table = new PackedCollection(
				new TraversalPolicy(FILTER_TABLE_BINS, taps));
		double[] data = new double[FILTER_TABLE_BINS * taps];
		double span = Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
		for (int b = 0; b < FILTER_TABLE_BINS; b++) {
			double cutoff = AudioPassFilter.MIN_FREQUENCY
					* Math.exp(span * b / (FILTER_TABLE_BINS - 1.0));
			biquadImpulseResponse(high, cutoff, sampleRate, data, b * taps, taps);
		}
		table.setMem(data);
		return table;
	}

	/**
	 * Builds a {@code [taps]} producer selecting the response-table row for the bin
	 * nearest the cutoff: {@code bin = floor(binScale * ln(cutoff / MIN_FREQUENCY) + 0.5)}.
	 * Entirely device-side (log, scale, floor, indexed gather).
	 *
	 * @param table    the {@code [FILTER_TABLE_BINS, taps]} response table
	 * @param cutoff   cutoff producer in Hz (bounded to the table's range)
	 * @param binScale {@code (FILTER_TABLE_BINS - 1) / ln(20000 / MIN_FREQUENCY)}
	 * @param taps     FIR taps per response
	 * @return a {@code [taps]} coefficient producer
	 */
	private static Producer<PackedCollection> tableRow(PackedCollection table,
			Producer<PackedCollection> cutoff, double binScale, int taps) {
		CollectionProducer bin = ADAPTER.floor(ADAPTER.add(
				ADAPTER.multiply(
						ADAPTER.log(ADAPTER.divide(cutoff,
								ADAPTER.c(AudioPassFilter.MIN_FREQUENCY))),
						ADAPTER.c(binScale)),
				ADAPTER.c(0.5)));
		Producer<PackedCollection> positions = ADAPTER.add(
				ADAPTER.multiply(bin, ADAPTER.c((double) taps)),
				ADAPTER.integers(0, taps));
		return ADAPTER.c(ADAPTER.shape(taps), ADAPTER.cp(table), positions);
	}

	/**
	 * Writes the TRUNCATED IMPULSE RESPONSE of
	 * {@link org.almostrealism.audio.filter.AudioPassFilter}'s biquad at the given cutoff
	 * into {@code out} — the FIR realisation of Java's exact filter. With
	 * {@code c = tan(PI*f/sr)} (high-pass) or its reciprocal (low-pass) and
	 * {@code a1 = 1/(1 + r*c + c*c)}, the IR follows the recurrence
	 * {@code y[n] = a1*x[n] + a2*x[n-1] + a3*x[n-2] - b1*y[n-1] - b2*y[n-2]} driven by a
	 * unit impulse. At audible cutoffs the biquad's poles decay within a handful of
	 * samples, so the truncation is essentially exact and the FIR matches Java's
	 * 12 dB/oct slope; a windowed-sinc FIR of the same order is far steeper, which audibly
	 * diverged from the CellList render during the cutoff sweep. Near-zero cutoffs leave a
	 * long IR tail that truncation drops, making the filter an identity passband there —
	 * matching Java's ~identity response at its bounded 10 Hz minimum cutoff. Runs on the
	 * host at the per-buffer step boundary: the recurrence cannot be expressed as a
	 * producer graph without an exponential expression tree (each unrolled step embeds
	 * copies of the two previous subtrees).
	 *
	 * @param high       true for the high-pass design; false for low-pass
	 * @param cutoff     cutoff frequency in Hz (already bounded to [10, 20000])
	 * @param sampleRate audio sample rate in Hz
	 * @param out        destination array
	 * @param offset     index of the first coefficient within {@code out}
	 * @param count      number of taps to write
	 */
	private static void biquadImpulseResponse(boolean high, double cutoff,
											  int sampleRate, double[] out,
											  int offset, int count) {
		double r = FixedFilterChromosome.defaultResonance;
		double t = Math.tan(Math.PI * cutoff / sampleRate);
		double c = high ? t : 1.0 / t;
		double a1 = 1.0 / (1.0 + r * c + c * c);
		double a2 = high ? -2.0 * a1 : 2.0 * a1;
		double a3 = a1;
		double b1 = high ? 2.0 * (c * c - 1.0) * a1 : 2.0 * (1.0 - c * c) * a1;
		double b2 = (1.0 - r * c + c * c) * a1;

		out[offset] = a1;
		if (count > 1) out[offset + 1] = a2 - b1 * out[offset];
		if (count > 2) out[offset + 2] = a3 - b1 * out[offset + 1] - b2 * out[offset];
		for (int n = 3; n < count; n++) {
			out[offset + n] = -b1 * out[offset + n - 1] - b2 * out[offset + n - 2];
		}
	}

	/**
	 * Builds the argument map for the {@code mixdown_master_wet} layer, adding the
	 * per-channel {@link EfxManager} feedforward parameters on top of the mixdown
	 * parameters from {@link #buildArgsMap(MixdownManager, Config)}.
	 *
	 * <p>The added arguments render {@link EfxManager#apply} (the per-channel chain applied to
	 * each voicing before the mixdown bus): the feedforward portion (gene-chosen filter, wet
	 * level, automation) plus the recursive feedback grid — the PDSL analogue of the mixdown
	 * efx bus's {@code .mself(transmission)} ({@code MixdownManager.createEfx}).</p>
	 *
	 * @param manager constructed mixdown manager (chromosomes already populated)
	 * @param efx     constructed effects manager (chromosomes already populated)
	 * @param config  structural configuration
	 * @return populated argument map for {@code PdslLoader.buildLayer(..., "mixdown_master_wet", ...)}
	 */
	public static Map<String, Object> buildArgsMap(MixdownManager manager, EfxManager efx, Config config) {
		Map<String, Object> args = buildArgsMap(manager, config);

		// efx_filter_coeffs: producer([channels, fir_taps]) — the per-channel gene-chosen
		// HP/LP coefficient bank from EfxManager.applyMixdownManager.java filter.
		args.put("efx_filter_coeffs", efxFilterCoefficients(efx, config));

		// efx_wet_level: producer([channels]) — delayLevels[ch,0] (maxWet already folded
		// into the gene transform), the per-channel wet send level in EfxManager.apply().
		args.put("efx_wet_level", perChannelProducer(config.channels, ch -> efxWetLevelProducer(efx, ch)));

		// efx_automation: [channels] slot — the 0.5*(1+automation_curve) modulation
		// EfxManager.apply() applies to the wet path. Time-varying (clock-driven), so it
		// is a collection slot refreshed per buffer by automationRefresh (see hp_cutoff).
		args.put("efx_automation", new PackedCollection(config.channels).fill(0.0));

		// Recursive feedback grid — the PDSL analogue of MixdownManager.createEfx's
		// .mself(fi(), transmission, fc(wetOut)). PDSL feedback is block-parallel
		// (frame-quantized), so it approximates the per-sample Java recurrence.
		//
		// efx_fb_delay: producer([channels]) — per-channel feedback delay in samples
		// (constant = config.delaySamples; must be < the ring buffer = signal_size).
		PackedCollection fbDelay = new PackedCollection(config.channels);
		double[] fbDelayData = new double[config.channels];
		for (int ch = 0; ch < config.channels; ch++) {
			fbDelayData[ch] = config.delaySamples;
		}
		fbDelay.setMem(fbDelayData);
		args.put("efx_fb_delay", fbDelay);

		// efx_fb_transmission: producer([channels, channels]) — the genome routing matrix
		// scaled to a guaranteed-contraction (max row sum <= feedbackGain < 1) so the
		// block-parallel feedback decays rather than diverges. Preserves the genome's
		// channel-to-channel routing pattern from MixdownManager's transmission chromosome.
		args.put("efx_fb_transmission", ADAPTER.multiply(transmissionMatrix(manager, config),
				ADAPTER.c(feedbackGain / config.channels)));

		// efx_fb_passthrough: producer([channels, channels]) — diagonal output level
		// (the wet/output gain of the echo), mirroring fc(wetOut) as a static wet level.
		args.put("efx_fb_passthrough", diagonalMatrix(config.channels, config.wetLevel));

		// Fresh feedback ring state: buffers span channels * signal_size (one frame; the
		// delay is < signal_size), heads one write position per channel.
		PackedCollection fbBuffers = new PackedCollection(config.channels * config.signalSize);
		fbBuffers.setMem(new double[config.channels * config.signalSize]);
		PackedCollection fbHeads = new PackedCollection(config.channels);
		fbHeads.setMem(new double[config.channels]);
		args.put("fb_buffers", fbBuffers);
		args.put("fb_heads", fbHeads);

		// Reverb bus — the PDSL analogue of MixdownManager.createEfx's
		// reverb.sum().map(DelayNetwork): a mono send through a multi-tap closed-loop
		// feedback delay network (the `delay_network` primitive). The send is summed into
		// the master alongside the dry and efx arms.
		//
		// reverb_send: [channels] slot — the per-channel reverb SEND, mirroring Java's
		// reverbFactor (MixdownManager.createCells/createEfx): wetSources.branch(..., reverbFactor)
		// sends only the reverb-channel subset, scaled by the reverb gene × reverbLevel × the
		// reverb automation. Channels not in reverbChannels send 0. Time-varying
		// (clock-driven automation), so it is a collection slot refreshed per buffer.
		args.put("reverb_send", new PackedCollection(config.channels).fill(0.0));

		// reverb_network_gain / reverb_tap_mean — the Java DelayNetwork's gain structure:
		// the send enters every delay line scaled by `gain` (DelayNetwork's default 0.1)
		// and the wet output is the MEAN of the lines (sum * 1/size), so the wet level
		// sits at roughly input * gain regardless of line count.
		args.put("reverb_network_gain", 0.1);
		args.put("reverb_tap_mean", 1.0 / config.channels);

		// reverb_delays: producer([channels]) — per-tap delay lengths spanning the multi-frame
		// reverb ring, spread across taps for diffusion (uniform taps give a metallic comb).
		args.put("reverb_delays", reverbTapDelays(config));

		// reverb_feedback: producer([channels, channels]) — a scaled Householder reflection,
		// the same feedback structure the Java DelayNetwork builds with
		// randomHouseholderMatrix(size, 1.0): an orthogonal reflection scaled by 1/size,
		// i.e. spectral radius 1/size. The previous 0.7 radius held ~2x the steady-state
		// energy and a much longer tail than Java, audibly inflating the reverb arm once
		// the automation drove the send past unity.
		args.put("reverb_feedback", householderMatrix(config.channels, 1.0 / config.channels));

		// Reverb ring state: a multi-frame ring (REVERB_FRAMES * signal_size) so the tail can
		// extend beyond one buffer; heads one write position per tap.
		int reverbRing = config.channels * REVERB_FRAMES * config.signalSize;
		PackedCollection reverbBuffers = new PackedCollection(reverbRing);
		reverbBuffers.setMem(new double[reverbRing]);
		PackedCollection reverbHeads = new PackedCollection(config.channels);
		reverbHeads.setMem(new double[config.channels]);
		args.put("reverb_buffers", reverbBuffers);
		args.put("reverb_heads", reverbHeads);

		// Diagnostic per-arm isolation gains (default 1.0). Mutable so a bisection test can zero
		// an individual arm of mixdown_master_wet to localize a level divergence.
		args.put("main_arm_gain", mainArmGain);
		args.put("efx_arm_gain", efxArmGain);
		args.put("reverb_arm_gain", reverbArmGain);

		// Initialise the efx-layer automation slots (see the matching call in the base map).
		automationRefresh(manager, efx, config, args).get().run();

		return args;
	}

	/** Diagnostic gain on the main (dry) arm of {@code mixdown_master_wet}; {@code 1.0} is faithful. */
	public static double mainArmGain = 1.0;

	/** Diagnostic gain on the efx (wet) arm of {@code mixdown_master_wet}; {@code 1.0} is faithful. */
	public static double efxArmGain = 1.0;

	/** Diagnostic gain on the reverb arm of {@code mixdown_master_wet}; {@code 1.0} is faithful. */
	public static double reverbArmGain = 1.0;

	/**
	 * Feedback-grid contraction target: the scaled genome transmission's maximum row sum is
	 * bounded by this value, keeping the block-parallel feedback stable (spectral radius is
	 * bounded by the induced infinity-norm, i.e. the max row sum). Mutable so diagnostics can
	 * bisect the efx feedback stage (set to 0 to disable feedback regeneration).
	 */
	public static double feedbackGain = 0.6;

	/**
	 * Global trim multiplier applied on top of the per-channel reverb send (the gene-driven
	 * {@code reverbFactor}). {@code 1.0} is the faithful Java level; mutable so diagnostics can
	 * bisect the reverb stage (set to 0 to disable the reverb bus).
	 */
	public static double reverbSend = 1.0;

	/** Number of log-spaced cutoff bins in the filter impulse-response lookup tables. */
	private static final int FILTER_TABLE_BINS = 1024;

	/** Reverb ring depth in frames; the per-tap delays span this multiple of signal_size. */
	private static final int REVERB_FRAMES = 2;

	/**
	 * Produces the shape-{@code [1]} per-channel reverb send level, mirroring the
	 * {@code reverbFactor} in {@link MixdownManager#createEfx}: a channel sends to the reverb
	 * bus only if it is in {@link MixdownManager#getReverbChannels()}, scaled by the reverb
	 * automation gene and {@link MixdownManager#reverbLevel}; all other channels send zero. The
	 * mutable {@link #reverbSend} trim is folded in so diagnostics can disable the bus.
	 *
	 * @param manager the mixdown manager whose reverb chromosomes are sampled
	 * @param channel the source channel index
	 * @return the per-channel reverb send producer
	 */
	private static Producer<PackedCollection> reverbSendProducer(MixdownManager manager, int channel) {
		if (reverbSend <= 0.0 || !manager.getReverbChannels().contains(channel)) {
			return ADAPTER.c(0.0);
		}
		Producer<PackedCollection> value = manager.getAutomationManager().getAggregatedValue(
				manager.getReverbAutomation().valueAt(channel),
				ADAPTER.p(manager.getReverbAdjustmentScale()), 0.0);
		return ADAPTER.multiply(value, ADAPTER.c(MixdownManager.reverbLevel * reverbSend));
	}

	/**
	 * Builds the {@code [channels]} per-tap reverb delay lengths, spread across the multi-frame
	 * ring (0.3 … 0.85 of the ring) so the taps are distinct and produce diffusion rather than a
	 * single coherent echo.
	 *
	 * @param config structural configuration
	 * @return a {@code [channels]} {@link PackedCollection} of per-tap delay sample counts
	 */
	private static PackedCollection reverbTapDelays(Config config) {
		int ring = REVERB_FRAMES * config.signalSize;
		PackedCollection delays = new PackedCollection(config.channels);
		double[] data = new double[config.channels];
		for (int ch = 0; ch < config.channels; ch++) {
			double fraction = 0.3 + 0.55 * (ch + 1.0) / (config.channels + 1.0);
			data[ch] = (int) (fraction * ring);
		}
		delays.setMem(data);
		return delays;
	}

	/**
	 * Builds a row-major scaled Householder reflection {@code gain * (I - 2 v vᵀ)} with
	 * {@code v} the unit vector of all {@code 1/√n}. The reflection is orthogonal (eigenvalues
	 * ±1), so the scaled matrix has spectral radius {@code gain} — a guaranteed-stable, fully
	 * channel-mixing feedback matrix for {@code gain < 1}.
	 *
	 * @param n    matrix dimension (tap count)
	 * @param gain scale factor / resulting spectral radius
	 * @return an {@code [n, n]} {@link PackedCollection} feedback matrix
	 */
	private static PackedCollection householderMatrix(int n, double gain) {
		PackedCollection matrix = new PackedCollection(new TraversalPolicy(n, n));
		double[] data = new double[n * n];
		double off = 2.0 / n;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double h = (i == j ? 1.0 : 0.0) - off;
				data[i * n + j] = gain * h;
			}
		}
		matrix.setMem(data);
		return matrix;
	}

	/**
	 * Builds a row-major {@code [n, n]} diagonal matrix slot with {@code value} on the diagonal.
	 *
	 * @param n     matrix dimension
	 * @param value the diagonal value
	 * @return a {@code [n, n]} {@link PackedCollection} diagonal matrix
	 */
	private static PackedCollection diagonalMatrix(int n, double value) {
		PackedCollection matrix = new PackedCollection(new TraversalPolicy(n, n));
		double[] data = new double[n * n];
		for (int i = 0; i < n; i++) {
			data[i * n + i] = value;
		}
		matrix.setMem(data);
		return matrix;
	}

	/**
	 * Builds the {@code [channels, fir_taps]} per-channel efx filter coefficient bank from the
	 * gene-driven cutoff ({@code 20000 * delayLevels[ch,3]}, clamped to a valid Nyquist range).
	 *
	 * <p><b>Wire-first approximation:</b> {@link EfxManager#applyFilter} selects high-pass OR
	 * low-pass per channel via the decision gene ({@code delayLevels[ch,2]}) using a runtime
	 * {@code choice(...)}. That selector is not generatable inside the compiled PDSL model
	 * graph, so this renders a per-channel low-pass only (the same shape as the mixdown wet
	 * filter). The high-pass option is dropped pending a graph-compatible per-channel filter
	 * selection.</p>
	 *
	 * @param efx    the effects manager whose chromosomes are sampled
	 * @param config structural configuration
	 * @return shape-{@code [channels, fir_taps]} coefficient producer
	 */
	private static Producer<PackedCollection> efxFilterCoefficients(EfxManager efx, Config config) {
		int firTaps = config.filterOrder + 1;
		Chromosome<PackedCollection> levels = efx.getDelayLevels();
		Producer<PackedCollection>[] perChannel = new Producer[config.channels];
		for (int ch = 0; ch < config.channels; ch++) {
			Producer<PackedCollection> cutoffUnit = levels.valueAt(ch, 3).getResultant(ADAPTER.c(1.0));
			Producer<PackedCollection> cutoffHz = ADAPTER.multiply(cutoffUnit, ADAPTER.c(20000.0));
			Producer<PackedCollection> clamped = ADAPTER.max(ADAPTER.c(20.0),
					ADAPTER.min(cutoffHz, ADAPTER.c(0.49 * config.sampleRate)));
			perChannel[ch] = ADAPTER.lowPassCoefficients(clamped, config.sampleRate, config.filterOrder);
		}
		CollectionProducer concatenated = ADAPTER.concat(perChannel);
		return concatenated.reshape(new TraversalPolicy(config.channels, firTaps));
	}

	/**
	 * Produces a shape-{@code [1]} per-channel efx wet-level multiplier
	 * ({@code delayLevels[ch,0]}).
	 *
	 * @param efx     the effects manager whose chromosomes are sampled
	 * @param channel the source channel index
	 * @return wet-level producer
	 */
	private static Producer<PackedCollection> efxWetLevelProducer(EfxManager efx, int channel) {
		return efx.getDelayLevels().valueAt(channel, 0).getResultant(ADAPTER.c(1.0));
	}

	/**
	 * Produces a shape-{@code [1]} per-channel efx automation modulation
	 * ({@code 0.5 * (1 + automation_curve)}), or unity when automation is disabled.
	 *
	 * @param efx     the effects manager whose chromosomes are sampled
	 * @param channel the source channel index
	 * @return automation modulation producer
	 */
	private static Producer<PackedCollection> efxAutomationProducer(EfxManager efx, int channel) {
		if (!EfxManager.enableAutomation) {
			return ADAPTER.c(1.0);
		}
		Producer<PackedCollection> value = efx.getAutomationManager().getAggregatedValue(
				efx.getDelayAutomation().valueAt(channel), null, 0.0);
		return ADAPTER.multiply(ADAPTER.c(0.5), ADAPTER.add(ADAPTER.c(1.0), value));
	}

	/**
	 * Builds a shape-{@code [channels]} producer by concatenating one
	 * shape-{@code [1]} producer per channel. Used to supply the per-channel
	 * automation arguments (HP cutoff, volume) that the PDSL layers subscript
	 * as {@code arg[channel]} inside {@code for each channel}.
	 *
	 * @param channels   number of channels
	 * @param perChannel supplies the shape-{@code [1]} producer for a channel index
	 * @return a shape-{@code [channels]} producer
	 */
	private static Producer<PackedCollection> perChannelProducer(
			int channels, IntFunction<Producer<PackedCollection>> perChannel) {
		Producer<PackedCollection>[] cols = new Producer[channels];
		for (int ch = 0; ch < channels; ch++) {
			cols[ch] = perChannel.apply(ch);
		}
		return ADAPTER.concat(cols).reshape(new TraversalPolicy(channels));
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
		if (hpCutoffOverrideHz >= 0.0) {
			// Diagnostic: force the main-arm high-pass cutoff (0 makes the spectral-inversion FIR
			// collapse to an identity passthrough), so a bisection can isolate the filter's effect
			// on the main-bus level from the rest of the chain.
			return ADAPTER.c(hpCutoffOverrideHz);
		}
		Producer<PackedCollection> v = manager.getAutomationManager().getAggregatedValue(
				manager.getMainFilterUpSimple().valueAt(channel),
				ADAPTER.p(manager.getMainFilterUpAdjustmentScale()), -40.0);
		// AudioPassFilter bounds its frequency producer to [MIN_FREQUENCY, 20000] at
		// construction, so the gene's near-zero cutoff is really a 10 Hz high-pass in
		// Java. Apply the identical bound so the FIR cutoff matches exactly.
		return ADAPTER.bound(ADAPTER.multiply(ADAPTER.c(20000.0), v),
				AudioPassFilter.MIN_FREQUENCY, 20000);
	}

	/** Diagnostic override for the main-arm high-pass cutoff in Hz; negative uses the gene value. */
	public static double hpCutoffOverrideHz = -1.0;

	/**
	 * Diagnostic: returns the six raw automation-gene component producers
	 * ({@code valueAt(0..5).getResultant(c(1))}) of the named mixdown gene for one channel, so a
	 * test can evaluate them and check whether the gene magnitudes are genuinely zero (filter
	 * disabled by the genome) or whether the adapter's {@code getAggregatedValue} read diverges
	 * from the Java CellList path.
	 *
	 * @param gene    the per-channel gene chromosome (e.g. {@code getMainFilterUpSimple()})
	 * @param channel the channel index
	 * @return the six component producers (phase 0-2, magnitude 3-5)
	 */
	public static Producer<PackedCollection>[] geneComponents(
			Chromosome<PackedCollection> gene, int channel) {
		Gene<PackedCollection> g = gene.valueAt(channel);
		Producer<PackedCollection>[] out = new Producer[6];
		for (int k = 0; k < 6; k++) {
			out[k] = g.valueAt(k).getResultant(ADAPTER.c(1.0));
		}
		return out;
	}

	/** Diagnostic accessor for the mainFilterUp gene chromosome. */
	public static Chromosome<PackedCollection> mainFilterUpGene(MixdownManager manager) {
		return manager.getMainFilterUpSimple();
	}

	/** Diagnostic accessor for the volume gene chromosome. */
	public static Chromosome<PackedCollection> volumeGene(MixdownManager manager) {
		return manager.getVolumeSimple();
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
		// AudioPassFilter bounds its frequency producer to [MIN_FREQUENCY, 20000]; apply
		// the identical bound so the master low-pass cutoff matches Java exactly.
		return ADAPTER.bound(ADAPTER.multiply(ADAPTER.c(20000.0), v),
				AudioPassFilter.MIN_FREQUENCY, 20000);
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
	 * Builds a {@code [channels, fir_taps]} producer-form per-channel low-pass
	 * FIR coefficient bank for the PDSL {@code wet_filter_coeffs} parameter.
	 * Each channel's coefficient row is computed via
	 * {@link org.almostrealism.time.TemporalFeatures#lowPassCoefficients(Producer, int, int)}
	 * driven by the wet-filter chromosome's low-pass frequency producer
	 * (gene factor 1, scaled to Hz and clamped to a valid Nyquist range).
	 * The N per-channel producers are concatenated and reshaped to a single
	 * {@code [channels, fir_taps]} producer.
	 *
	 * <p>The Java path's wet filter is an {@link org.almostrealism.audio.filter.AudioPassFilter}
	 * IIR chain (see {@link FixedFilterChromosome.FixedFilterGene#valueAt}); the
	 * PDSL {@code fir} primitive consumes FIR coefficients. This is the
	 * structural mismatch the planning document calls out — the test in
	 * {@code MixdownManagerPdslVerificationTest} compares the two with that
	 * caveat documented.</p>
	 *
	 * @param manager the mixdown manager whose wet-filter chromosome is sampled
	 * @param config  structural configuration
	 * @return shape-{@code [channels, fir_taps]} coefficient producer
	 */
	private static Producer<PackedCollection> wetFilterCoefficients(MixdownManager manager, Config config) {
		final int firTaps = config.filterOrder + 1;
		Chromosome<PackedCollection> wetFilter = manager.getWetFilter();
		Producer<PackedCollection>[] perChannel = new Producer[config.channels];
		for (int ch = 0; ch < config.channels; ch++) {
			Producer<PackedCollection> lpUnit = wetFilter.valueAt(ch).valueAt(1)
					.getResultant(ADAPTER.c(1.0));
			Producer<PackedCollection> lpHz = ADAPTER.multiply(lpUnit, ADAPTER.c(20000.0));
			Producer<PackedCollection> lpHzClamped = ADAPTER.max(ADAPTER.c(20.0),
					ADAPTER.min(lpHz, ADAPTER.c(0.49 * config.sampleRate)));
			perChannel[ch] = ADAPTER.lowPassCoefficients(
					lpHzClamped, config.sampleRate, config.filterOrder);
		}
		CollectionProducer concatenated = ADAPTER.concat(perChannel);
		return concatenated.reshape(new TraversalPolicy(config.channels, firTaps));
	}

	/**
	 * Builds a {@code [channels, channels]} cross-channel transmission matrix
	 * producer from the wet-bus transmission chromosome. Mirrors the
	 * {@code mself(fi(), transmission, ...)} call at
	 * {@link MixdownManager#createEfx} by composing each gene-pair's
	 * resultant producer into a single {@code [channels, channels]} producer
	 * via {@code concat} and {@code reshape}. The PDSL substrate re-evaluates
	 * the producer on every forward pass, so chromosome-state changes flow
	 * through the routing matrix without rebuilding the layer. Cells beyond
	 * the chromosome's extent are zero.
	 *
	 * @param manager the mixdown manager whose transmission chromosome is sampled
	 * @param config  structural configuration
	 * @return shape-{@code [channels, channels]} routing matrix producer
	 */
	private static Producer<PackedCollection> transmissionMatrix(MixdownManager manager, Config config) {
		Chromosome<PackedCollection> chrom = manager.getTransmission();
		int rows = Math.min(chrom.length(), config.channels);
		Producer<PackedCollection>[] cells = new Producer[config.channels * config.channels];
		for (int n = 0; n < config.channels; n++) {
			int cols = (n < rows) ? Math.min(chrom.valueAt(n).length(), config.channels) : 0;
			for (int m = 0; m < config.channels; m++) {
				if (m < cols) {
					cells[n * config.channels + m] = chrom.valueAt(n).valueAt(m)
							.getResultant(ADAPTER.c(1.0));
				} else {
					cells[n * config.channels + m] = ADAPTER.c(0.0);
				}
			}
		}
		CollectionProducer concatenated = ADAPTER.concat(cells);
		return concatenated.reshape(new TraversalPolicy(config.channels, config.channels));
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
