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
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.model.Block;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.studio.optimize.OptimizeFactorFeatures;
import org.almostrealism.util.FirFilterTestFeatures;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
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
		/** Number of audio channels (equal to {@code channelIndices.length}). */
		public final int channels;
		/**
		 * The scene channel index rendered at each bank position. Argument banks are
		 * always built at zero-based positions {@code 0..channels-1} (matching the
		 * consolidated render buffer's row order), but each position's genome reads must
		 * resolve to the <em>actual</em> scene channel selected for that position — so
		 * {@link AudioScene#renderChannel}'s single-channel selection {@code [c]} reads
		 * channel {@code c}'s genes rather than channel 0's. For the multi-channel
		 * zero-based contiguous selection this is the identity {@code [0,1,...,n-1]}, so
		 * the mapping is a no-op there. Indexed via {@link #channel(int)}.
		 */
		public final int[] channelIndices;
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
		 * Creates a configuration record using the project default master-bus gain and the
		 * identity channel mapping (bank position {@code p} reads channel {@code p}).
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
		 * Creates a configuration record with an explicit master-bus gain and the identity
		 * channel mapping. The PDSL {@code mixdown_master} layer applies
		 * {@code scale(master_gain)} followed by a hard {@code clip(-1, 1)} as its master
		 * limiter stage, mirroring {@code MixdownManager.createEfx()}.
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
			this(identityChannels(channels), signalSize, sampleRate, filterOrder,
					wetLevel, delaySamples, masterBusGain);
		}

		/**
		 * Creates a configuration record for an explicit channel selection using the
		 * project default master-bus gain.
		 *
		 * @param channelIndices the scene channel index for each bank position (non-empty)
		 * @param signalSize     samples per pass
		 * @param sampleRate     audio sample rate (Hz)
		 * @param filterOrder    FIR filter order
		 * @param wetLevel       wet-send scalar level
		 * @param delaySamples   static integer delay length in samples
		 */
		public Config(int[] channelIndices, int signalSize, int sampleRate, int filterOrder,
					  double wetLevel, int delaySamples) {
			this(channelIndices, signalSize, sampleRate, filterOrder, wetLevel, delaySamples,
					MixdownManager.masterBusGain);
		}

		/**
		 * Creates a configuration record for an explicit channel selection. Used when the
		 * rendered selection is not the zero-based contiguous prefix — notably the
		 * single-channel selection {@code [c]} from {@link AudioScene#renderChannel} — so
		 * the adapter's per-channel genome reads resolve to the selected scene channels
		 * while the argument banks stay zero-based.
		 *
		 * @param channelIndices the scene channel index for each bank position (non-empty)
		 * @param signalSize     samples per pass
		 * @param sampleRate     audio sample rate (Hz)
		 * @param filterOrder    FIR filter order
		 * @param wetLevel       wet-send scalar level
		 * @param delaySamples   static integer delay length in samples
		 * @param masterBusGain  master-bus headroom multiplier
		 */
		public Config(int[] channelIndices, int signalSize, int sampleRate, int filterOrder,
					  double wetLevel, int delaySamples, double masterBusGain) {
			this.channelIndices = channelIndices.clone();
			this.channels = this.channelIndices.length;
			this.signalSize = signalSize;
			this.sampleRate = sampleRate;
			this.filterOrder = filterOrder;
			this.wetLevel = wetLevel;
			this.delaySamples = delaySamples;
			this.masterBusGain = masterBusGain;
		}

		/**
		 * Returns the scene channel index whose genes drive the bank at the given
		 * zero-based position.
		 *
		 * @param position the zero-based bank position ({@code 0..channels-1})
		 * @return the scene channel index to read genome state from
		 */
		public int channel(int position) {
			return channelIndices[position];
		}

		/**
		 * Builds the identity channel mapping {@code [0,1,...,channels-1]}.
		 *
		 * @param channels the channel count
		 * @return the identity index array
		 */
		private static int[] identityChannels(int channels) {
			int[] indices = new int[channels];
			for (int i = 0; i < channels; i++) {
				indices[i] = i;
			}
			return indices;
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
		args.put("wet_filter_coeffs", wetFilterCoefficients(manager, config, false));

		// wet_hp_coeffs: producer([channels, fir_taps]) — the high-pass half of the
		// legacy wet-filter cascade (AudioPassFilter HP then LP); only the
		// mixdown_master_wet layer declares it, and unknown keys are ignored elsewhere.
		args.put("wet_hp_coeffs", wetFilterCoefficients(manager, config, true));

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
		// The PDSL master path applies clip(-1, 1) after this scale — the same hard
		// limiter the Java path uses (an earlier tanh_act() soft saturator bent the
		// signal even at matched levels and was replaced).
		args.put("master_gain", config.masterBusGain);

		// buffers, heads — fresh state slots for the per-channel feedforward delay.
		// The delay stage is write-first, so a ring only holds samples of age D for
		// D <= ring - signal_size: the ring must span ceil((delaySamples + signalSize)
		// / signalSize) whole frames. The former one-frame allocation could not hold
		// ANY positive delay — at 4096 the 6500-sample delay degenerated into a
		// non-causal within-frame rotation with a splice every buffer (the audible
		// defect this sizing closed). 'buffers[channel]' carves ring-per-channel;
		// 'heads[channel]' carves 1 head per channel.
		int ffFrames = (config.delaySamples + 2 * config.signalSize - 1) / config.signalSize;
		int ffRing = ffFrames * config.signalSize;
		PackedCollection buffers = new PackedCollection(config.channels * ffRing).fill(0.0);
		PackedCollection heads = new PackedCollection(config.channels).fill(0.0);
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
	 * {@code lp_cutoff}, and — when {@code efx} is provided — {@code efx_automation},
	 * {@code reverb_send}, and {@code wet_in}). The real-time runner runs this once per
	 * buffer, before the
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

		// Ramp history: copy each hot-bus gain's current value into its _prev slot
		// BEFORE the current slots are re-evaluated below, so the compiled ramp_scale
		// stages interpolate from last buffer's end value to this buffer's target.
		// These copies must stay ahead of the corresponding current-slot assignments;
		// list order is preserved through compilation (read-after-write hazards
		// subdivide the operation list).
		PackedCollection volumePrev = (PackedCollection) args.get("volume_prev");
		if (volumePrev != null) {
			refresh.add(ADAPTER.a(config.channels, ADAPTER.cp(volumePrev),
					ADAPTER.cp(volume)));
			refresh.add(ADAPTER.a(config.channels,
					ADAPTER.cp((PackedCollection) args.get("efx_automation_prev")),
					ADAPTER.cp((PackedCollection) args.get("efx_automation"))));
			refresh.add(ADAPTER.a(config.channels,
					ADAPTER.cp((PackedCollection) args.get("reverb_send_prev")),
					ADAPTER.cp((PackedCollection) args.get("reverb_send"))));
			PackedCollection wetInPrev = (PackedCollection) args.get("wet_in_prev");
			if (wetInPrev != null) {
				refresh.add(ADAPTER.a(config.channels, ADAPTER.cp(wetInPrev),
						ADAPTER.cp((PackedCollection) args.get("wet_in"))));
			}
		}
		for (int ch = 0; ch < config.channels; ch++) {
			refresh.add(ADAPTER.a(1, ADAPTER.cp(hpCutoff.range(ADAPTER.shape(1), ch)),
					hpCutoffProducer(manager, config.channel(ch))));
			refresh.add(ADAPTER.a(1, ADAPTER.cp(volume.range(ADAPTER.shape(1), ch)),
					volumeProducer(manager, config.channel(ch))));
		}
		// Master low-pass: a single post-sum filter (lp_cutoff is shape [1]). The Java path
		// applies it after main.sum() and reads channelIndex(0) — the first selected channel's
		// mainFilterDown gene. Sourcing the one global cutoff from the zeroth selected channel
		// is a convenience (the long-run intent is a single/global gene, not per-channel); for
		// a single-channel render this resolves to that channel's own gene.
		refresh.add(ADAPTER.a(1, ADAPTER.cp(lpCutoff), lpCutoffProducer(manager, config.channel(0))));

		// The FIR coefficient slots hold the TRUNCATED IMPULSE RESPONSE of Java's
		// AudioPassFilter biquad at the current cutoff. The IR recurrence cannot be built
		// as a producer graph (each unrolled step embeds copies of the two previous
		// subtrees — an exponential expression tree), so the responses are tabulated once
		// at build time over log-spaced cutoff bins and each refresh SELECTS a row with a
		// device-side gather: bin = round((bins-1) * ln(cutoff/10) / ln(20000/10)).
		PackedCollection hpTable = biquadResponseTable(true, config.sampleRate, taps);
		PackedCollection lpTable = biquadResponseTable(false, config.sampleRate, taps);
		double binScale = FILTER_TABLE_BIN_SCALE;
		for (int ch = 0; ch < config.channels; ch++) {
			refresh.add(ADAPTER.a(taps,
					ADAPTER.cp(hpCoeffs.range(ADAPTER.shape(taps), ch * taps)),
					tableRow(hpTable, hpCutoffProducer(manager, config.channel(ch)), binScale, taps)));
		}
		refresh.add(ADAPTER.a(taps, ADAPTER.cp(lpCoeffs),
				tableRow(lpTable, lpCutoffProducer(manager, config.channel(0)), binScale, taps)));

		if (efx != null) {
			PackedCollection efxAutomation = (PackedCollection) args.get("efx_automation");
			PackedCollection reverbSend = (PackedCollection) args.get("reverb_send");
			for (int ch = 0; ch < config.channels; ch++) {
				refresh.add(ADAPTER.a(1, ADAPTER.cp(efxAutomation.range(ADAPTER.shape(1), ch)),
						efxAutomationProducer(efx, config.channel(ch))));
				refresh.add(ADAPTER.a(1, ADAPTER.cp(reverbSend.range(ADAPTER.shape(1), ch)),
						reverbSendProducer(manager, config.channel(ch))));
			}

			// wet_in: the clock-automated wetInSimple send gene (the gain at which each
			// channel's wet voicing enters the bus-line network), refreshed per buffer
			// like the other adjustment genes and ramped via wet_in_prev above.
			PackedCollection wetIn = (PackedCollection) args.get("wet_in");
			if (wetIn != null) {
				for (int ch = 0; ch < config.channels; ch++) {
					refresh.add(ADAPTER.a(1, ADAPTER.cp(wetIn.range(ADAPTER.shape(1), ch)),
							wetInProducer(manager, config.channel(ch))));
				}
			}

			// Bus-line delay modulation: the legacy AdjustableDelayCells advance BOTH
			// cursors at the delayDynamics gene's polycyclic rate s(t) (a periodic
			// wobble times a slow accelerando), so the write-read separation is a FIXED
			// timeline distance and the EFFECTIVE delay is the ratio gene / s(t) —
			// bounded, memoryless, re-evaluated here once per buffer, one rate gene per
			// BUS LINE (the legacy df.apply(i) per delay layer). Because s reads the
			// clock, it re-runs from its curve's start at every arrangement-section
			// reset, snapping the wash back wide — the legacy sectional structure. (An
			// earlier revision integrated (1 - s) per buffer instead; the unbounded
			// integral dragged every line to the one-frame floor within a minute of
			// audio, and the faithful unscaled recirculation then blew the loop up into
			// a permanent full-scale buzz.) The rate change's pitch bend — content
			// written at rate s1 and read at s2 emerges resampled by s2/s1 — is the
			// resampling-read arc; here the delay moves in whole-sample per-buffer
			// steps.
			Object busDelaySlot = args.get("bus_delay_samples");
			if (busDelaySlot instanceof PackedCollection) {
				PackedCollection busDelays = (PackedCollection) busDelaySlot;
				int layers = busDelays.getShape().getTotalSize();
				for (int j = 0; j < layers; j++) {
					refresh.add(ADAPTER.a(1, ADAPTER.cp(busDelays.range(ADAPTER.shape(1), j)),
							busLineDelay(manager, config, j)));
				}
			}
		}

		return refresh;
	}

	/**
	 * Builds the {@code [FILTER_TABLE_BINS, taps]} table of truncated biquad impulse
	 * responses over log-spaced cutoffs in {@code [MIN_FREQUENCY, 20000]} Hz, entirely as a
	 * {@link CollectionProducer} graph evaluated once at argument-build time; per-buffer
	 * refresh selects a row with a device-side gather.
	 *
	 * <p>The table is the FIR realisation of
	 * {@link org.almostrealism.audio.filter.AudioPassFilter}'s biquad. With
	 * {@code c = tan(PI*f/sr)} (high-pass) or its reciprocal (low-pass) and
	 * {@code a1 = 1/(1 + r*c + c*c)}, the impulse response follows the recurrence
	 * {@code y[n] = a1*x[n] + a2*x[n-1] + a3*x[n-2] - b1*y[n-1] - b2*y[n-2]} driven by a unit
	 * impulse. Rather than unroll that recurrence (whose naive expression tree grows
	 * exponentially), the response is generated in closed form: for the project resonance
	 * the discriminant {@code b1*b1 - 4*b2} reduces to {@code -c*c*(4 - r*r) < 0}, so the
	 * poles are <em>always</em> a complex-conjugate pair {@code rho*exp(+/- i*theta)} with
	 * {@code rho = sqrt(b2)} and {@code theta = acos(-b1 / (2*rho))}. The homogeneous IR is
	 * therefore {@code y[n] = rho^n * (A*cos(n*theta) + B*sin(n*theta))} for {@code n >= 1}
	 * (with {@code A}, {@code B} fit to {@code y[1]}, {@code y[2]}), and {@code y[0] = a1}.
	 * Every coefficient is a per-bin scalar producer; the {@code [bins, taps]} table is the
	 * outer combination of those with the tap index. This matches Java's exact 12 dB/oct
	 * biquad (a windowed-sinc FIR of the same order is far steeper and audibly diverged from
	 * the CellList render during the cutoff sweep).</p>
	 *
	 * @param high       true for the high-pass design; false for low-pass
	 * @param sampleRate audio sample rate in Hz
	 * @param taps       FIR taps per response
	 * @return the response table
	 */
	public static PackedCollection biquadResponseTable(boolean high, int sampleRate, int taps) {
		int bins = FILTER_TABLE_BINS;
		int tail = taps - 1;
		double r = FixedFilterChromosome.defaultResonance;
		double span = Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
		double piOverSr = Math.PI / sampleRate;

		// Per-bin scalar coefficients (shape [bins]).
		CollectionProducer cutoff = ADAPTER.exp(
				ADAPTER.integers(0, bins).multiply(span / (bins - 1.0)))
				.multiply(AudioPassFilter.MIN_FREQUENCY);
		CollectionProducer t = ADAPTER.tan(cutoff.multiply(piOverSr));
		CollectionProducer c = high ? t : t.pow(-1.0);
		CollectionProducer csq = c.multiply(c);
		CollectionProducer rc = c.multiply(r);
		CollectionProducer a1 = ADAPTER.add(rc, csq).add(1.0).pow(-1.0);
		CollectionProducer a2 = high ? a1.multiply(-2.0) : a1.multiply(2.0);
		CollectionProducer a3 = a1;
		CollectionProducer b1 = high
				? csq.subtract(1.0).multiply(2.0).multiply(a1)
				: csq.multiply(-1.0).add(1.0).multiply(2.0).multiply(a1);
		CollectionProducer b2 = ADAPTER.add(rc.multiply(-1.0), csq).add(1.0).multiply(a1);

		CollectionProducer rho = ADAPTER.sqrt(b2);
		CollectionProducer theta = ADAPTER.acos(
				ADAPTER.divide(b1.multiply(-1.0), rho.multiply(2.0)));

		// Impulse-response seeds y[0], y[1], y[2].
		CollectionProducer y0 = a1;
		CollectionProducer y1 = ADAPTER.subtract(a2, ADAPTER.multiply(b1, y0));
		CollectionProducer y2 = ADAPTER.subtract(
				ADAPTER.subtract(a3, ADAPTER.multiply(b1, y1)), ADAPTER.multiply(b2, y0));

		// Closed-form amplitudes: det = cos(t)sin(2t) - cos(2t)sin(t) = sin(t).
		CollectionProducer c1 = ADAPTER.cos(theta);
		CollectionProducer s1 = ADAPTER.sin(theta);
		CollectionProducer c2 = ADAPTER.cos(theta.multiply(2.0));
		CollectionProducer s2 = ADAPTER.sin(theta.multiply(2.0));
		CollectionProducer y1n = ADAPTER.divide(y1, rho);
		CollectionProducer y2n = ADAPTER.divide(y2, b2);
		CollectionProducer aCoef = ADAPTER.divide(
				ADAPTER.subtract(ADAPTER.multiply(y1n, s2), ADAPTER.multiply(y2n, s1)), s1);
		CollectionProducer bCoef = ADAPTER.divide(
				ADAPTER.subtract(ADAPTER.multiply(y2n, c1), ADAPTER.multiply(y1n, c2)), s1);

		// Tap-indexed response for n >= 1. repeat(axis, n) inserts a new dimension, so a
		// [bins] column repeated on axis 1 and a [tail] row repeated on axis 0 both broadcast
		// to [bins, tail]; their product is the n*theta / n*ln(rho) outer combination.
		CollectionProducer nRow = ADAPTER.integers(0, tail).add(1.0).repeat(0, bins);
		CollectionProducer nTheta = ADAPTER.multiply(theta.repeat(1, tail), nRow);
		CollectionProducer rhoPow = ADAPTER.exp(
				ADAPTER.multiply(ADAPTER.log(rho).repeat(1, tail), nRow));
		CollectionProducer aTile = aCoef.repeat(1, tail);
		CollectionProducer bTile = bCoef.repeat(1, tail);
		CollectionProducer general = ADAPTER.multiply(rhoPow,
				ADAPTER.add(ADAPTER.multiply(aTile, ADAPTER.cos(nTheta)),
						ADAPTER.multiply(bTile, ADAPTER.sin(nTheta))));

		// Prepend y[0] = a1 as the first tap, materialising the table at the build boundary.
		CollectionProducer response = ADAPTER.concat(1, a1.reshape(bins, 1), general);
		PackedCollection table = new PackedCollection(new TraversalPolicy(bins, taps));
		ADAPTER.a(bins * taps, ADAPTER.cp(table), response).get().run();
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
	 * Builds the argument map for the {@code mixdown_master_wet} layer, adding the
	 * per-channel {@link EfxManager} parameters and the bus-line network on top of the
	 * mixdown parameters from {@link #buildArgsMap(MixdownManager, Config)}.
	 *
	 * <p>The added arguments render the two distinct legacy regeneration structures:
	 * {@link EfxManager#apply}'s per-channel echo (gene-chosen filter, wet level,
	 * automation, delay at the {@code delayTimes} gene with per-channel self-feedback —
	 * applied to BOTH voicings, each arm with its own ring state) and
	 * {@code MixdownManager.createEfx}'s bus-line network ({@code wetInSimple}-gated
	 * send into the first line, {@code delay}-chromosome line lengths with per-line
	 * drift, unscaled genome transmission recirculation, {@code wetOut} output taps).</p>
	 *
	 * @param manager constructed mixdown manager (chromosomes already populated)
	 * @param efx     constructed effects manager (chromosomes already populated)
	 * @param config  structural configuration
	 * @return populated argument map for {@code PdslLoader.buildLayer(..., "mixdown_master_wet", ...)}
	 */
	public static Map<String, Object> buildArgsMap(MixdownManager manager, EfxManager efx, Config config) {
		Map<String, Object> args = buildArgsMap(manager, config);

		// mixdown_master_wet renders the legacy mixdown-bus delay layer as the SHARED
		// bus-line network below (bus_* arguments), not as the base map's per-channel
		// feedforward delay — remove the base rings and the static delay so nothing
		// holds their memory.
		((PackedCollection) args.remove("buffers")).destroy();
		((PackedCollection) args.remove("heads")).destroy();
		args.remove("delay_samples");

		// efx_filter_coeffs: producer([channels, fir_taps]) — the per-channel gene-chosen
		// HP/LP coefficient bank matching EfxManager.applyFilter (decision gene picks HP vs LP).
		args.put("efx_filter_coeffs", efxFilterCoefficients(efx, config));

		// efx_wet_level: producer([channels]) — delayLevels[ch,0] (maxWet already folded
		// into the gene transform), the per-channel wet send level in EfxManager.apply().
		args.put("efx_wet_level",
				perChannelProducer(config.channels, ch -> efxWetLevelProducer(efx, config.channel(ch))));

		// efx_automation: [channels] slot — the 0.5*(1+automation_curve) modulation
		// EfxManager.apply() applies to the wet path. Time-varying (clock-driven), so it
		// is a collection slot refreshed per buffer by automationRefresh (see hp_cutoff).
		args.put("efx_automation", new PackedCollection(config.channels).fill(0.0));

		// Ramp history slots: each hot-bus gain keeps the value its ramp ended on last
		// buffer, copied from the current slot at the top of every automationRefresh, so
		// the ramp_scale stages interpolate buffer-to-buffer instead of stepping. The
		// per-buffer gain staircase (43 Hz at 1024) was a level discontinuity at every
		// buffer boundary on loud buses, recirculated by the feedback loops — the
		// legacy path applied these gains per sample.
		args.put("volume_prev", new PackedCollection(config.channels).fill(0.0));
		args.put("efx_automation_prev", new PackedCollection(config.channels).fill(0.0));
		args.put("reverb_send_prev", new PackedCollection(config.channels).fill(0.0));

		// wet_in: [channels] slot — the clock-automated wetInSimple send gene, the gain at
		// which each channel enters the bus-line network (legacy delayGene routes every
		// channel into delay layer 0 scaled by this adjustment; its range configuration
		// starts the send closed and opens it over the arrangement). Time-varying, so a
		// slot refreshed per buffer and ramped like the other hot-bus gains.
		args.put("wet_in", new PackedCollection(config.channels).fill(0.0));
		args.put("wet_in_prev", new PackedCollection(config.channels).fill(0.0));

		// Per-channel apply echo — EfxManager.apply's AdjustableDelayCell: the filtered,
		// leveled, automation-modulated wet branch feeds a per-channel delay at the
		// delayTimes gene with per-channel self-feedback, and the branch emits the PURE
		// DELAYED tap (delay_network semantics), summed with the undelayed dry by the
		// surrounding accum. Legacy applies this at the pattern-channel level, so BOTH
		// voicings carry it: the MAIN arm and the WET arm each run the stage with their
		// own ring state (main_fb_* / fb_*) over the same gene-driven parameters.
		//
		// efx_fb_delay: producer([channels]) — per-channel echo delay in samples from the
		// EfxManager delay-time gene (delayTimes[ch,0] beat-multiple × beatDuration × sampleRate),
		// mirroring the AdjustableDelayCell delay in EfxManager.apply. Floored to whole samples for
		// an integer ring index; the rings below are sized so every gene delay fits.
		double beatSamples = efx.getBeatDuration() * efx.getSampleRate();
		args.put("efx_fb_delay", perChannelProducer(config.channels, ch -> ADAPTER.floor(
				ADAPTER.multiply(efx.getDelayTimes().valueAt(config.channel(ch), 0)
						.getResultant(ADAPTER.c(1.0)), ADAPTER.c(beatSamples)))));

		// efx_fb_transmission: producer([channels, channels]) — DIAGONAL-ONLY: the
		// per-channel self-feedback gene (delayLevels[ch,1], already bounded by the
		// EfxManager maxFeedback transform). The legacy apply echo has no cross-channel
		// coupling; the genome transmission matrix belongs to the bus-line network
		// (bus_transmission below), where it recirculates at the bus-delay period.
		args.put("efx_fb_transmission", echoFeedbackMatrix(efx, config));

		// Fresh echo ring state, one bank per voicing arm. Each ring spans whole frames
		// (feedbackNetworkBlock requires the per-channel buffer to be a multiple of
		// signal_size) sized so the longest gene delay fits: ceil((maxDelay + 1) /
		// signal_size) frames, where maxDelay covers the gene's delay ceiling
		// (MAX_FEEDBACK_DELAY_BEATS). One write head per channel per arm.
		int maxDelaySamples = (int) Math.ceil(MAX_FEEDBACK_DELAY_BEATS * beatSamples);
		int fbFrames = Math.max(1, (maxDelaySamples + 1 + config.signalSize - 1) / config.signalSize);
		int fbRing = fbFrames * config.signalSize;
		args.put("fb_buffers", new PackedCollection(config.channels * fbRing).fill(0.0));
		args.put("fb_heads", new PackedCollection(config.channels).fill(0.0));
		args.put("main_fb_buffers", new PackedCollection(config.channels * fbRing).fill(0.0));
		args.put("main_fb_heads", new PackedCollection(config.channels).fill(0.0));

		// Bus-line network — MixdownManager.createEfx's delay layers: every channel's
		// wet voicing (post filter/volume, scaled by wet_in) is routed into the FIRST
		// line ONLY (bus_send, the legacy delayGene routing); the remaining lines
		// receive nothing but the
		// M×M genome transmission recirculation (bus_transmission, UNSCALED — legacy runs
		// the raw genes, whose row sums can exceed 1; runaway is real in the legacy path
		// too and the master clip bounds it); each line is an AdjustableDelayCell whose
		// EFFECTIVE length is the `delay` chromosome's 4-20 s gene divided by the
		// clock-driven cursor rate (see automationRefresh — the legacy cell advances
		// BOTH cursors at the rate, so the write-read separation is fixed and the rate
		// rescales the delay, never accumulates); the network emits wetOut[j] × line j's
		// PURE DELAYED tap (bus_wet_out on the feedback stage's passthrough), summed to
		// the mono efx bus.
		int layers = Math.max(1, manager.getDelay().length());
		args.put("delay_layers", layers);

		// bus_delay_samples: [layers] slot — the effective per-line delay, re-evaluated
		// once per buffer by automationRefresh (time-varying: the cursor rate reads the
		// clock). Initialised by the refresh run at the end of this method.
		PackedCollection busDelays = new PackedCollection(layers).fill(0.0);
		args.put("bus_delay_samples", busDelays);
		args.put("bus_send", busSendMatrix(config.channels, layers));
		args.put("bus_transmission", busTransmissionMatrix(manager, layers));
		args.put("bus_wet_out", busWetOutMatrix(manager, layers));

		// Bus ring state, sized from the current genome's largest evaluated BASE delay
		// plus headroom for slow-down excursions (a cursor rate below 1 lengthens the
		// effective delay past the gene); the kernel's ring-band clamp bounds anything
		// beyond it (e.g. a deeper slow-down or a live-swapped genome with longer genes).
		int busFrames = (maxEvaluated(perChannelProducer(layers,
				j -> busLineBaseDelay(manager, config, j)), layers) * 5 / 4
				+ 3 * config.signalSize - 1) / config.signalSize;
		args.put("bus_buffers", new PackedCollection(
				layers * busFrames * config.signalSize).fill(0.0));
		args.put("bus_heads", new PackedCollection(layers).fill(0.0));

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
		args.put("reverb_tap_mean", 1.0 / reverbTaps);

		// reverb_taps: the delay-line count, decoupled from the scene channel count. The
		// Java DelayNetwork defaults to 128 random lines over 0.15-1.5 s; a line count
		// tied to channels (formerly 6) with a 2-frame ring produced 6 regular
		// flutter-range taps (~70-143 ms at 4096) — a small metallic room instead of the
		// legacy diffusion splash. The mono send is fanned across the taps by
		// repeat(reverb_taps) in the PDSL layer.
		args.put("reverb_taps", reverbTaps);

		// reverb_delays: producer([reverb_taps]) — per-tap delay lengths spread over the
		// legacy DelayNetwork's 0.15-1.5 s range (seconds-denominated, so the room no
		// longer changes size with the buffer), quasi-randomly but deterministically
		// spaced (uniform taps give a metallic comb).
		args.put("reverb_delays", reverbTapDelays(config));

		// reverb_feedback: producer([reverb_taps, reverb_taps]) — a scaled Householder
		// reflection, the same feedback structure the Java DelayNetwork builds with
		// randomHouseholderMatrix(size, 1.0): an orthogonal reflection scaled by 1/size,
		// i.e. spectral radius 1/size. The previous 0.7 radius held ~2x the steady-state
		// energy and a much longer tail than Java, audibly inflating the reverb arm once
		// the automation drove the send past unity.
		args.put("reverb_feedback", householderMatrix(reverbTaps, 1.0 / reverbTaps));

		// Reverb ring state: the delay_network stage is read-first, so a ring holds
		// samples of age D for signal_size <= D <= ring; sizing the ring to the tail's
		// longest tap keeps every tap inside that band. One write head per tap.
		int reverbRing = reverbRingFrames(config) * config.signalSize;
		PackedCollection reverbBuffers = new PackedCollection(reverbTaps * reverbRing).fill(0.0);
		PackedCollection reverbHeads = new PackedCollection(reverbTaps).fill(0.0);
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
	 * Global trim multiplier applied on top of the per-channel reverb send (the gene-driven
	 * {@code reverbFactor}). {@code 1.0} is the faithful Java level; mutable so diagnostics can
	 * bisect the reverb stage (set to 0 to disable the reverb bus).
	 */
	public static double reverbSend = 1.0;

	/**
	 * Diagnostic multiplier on both regeneration paths — the apply echo's self-feedback
	 * diagonal and the bus-line transmission recirculation. {@code 1.0} is faithful (the
	 * raw gene values); mutable so diagnostics can zero regeneration to localize runaway
	 * or grit to the recirculating stages (at 0 the echo and the bus lines fire their
	 * first delayed tap only, with no decay train). Read at argument-build time.
	 */
	public static double regenerationGain = 1.0;

	/** Number of log-spaced cutoff bins in the filter impulse-response lookup tables. */
	private static final int FILTER_TABLE_BINS = 1024;

	/**
	 * Bin-selection scale for the impulse-response tables:
	 * {@code (FILTER_TABLE_BINS - 1) / ln(20000 / MIN_FREQUENCY)} (see {@link #tableRow}).
	 */
	private static final double FILTER_TABLE_BIN_SCALE =
			(FILTER_TABLE_BINS - 1) / Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);

	/**
	 * Reverb delay-line count. Decoupled from the scene channel count so tap density is
	 * a diffusion choice, not a topology accident: the Java {@link DelayNetwork} the
	 * reverb arm mirrors defaults to 128 random lines, and per-line recirculation at
	 * spectral radius {@code 1/taps} is near-negligible either way, so the audible tail
	 * is essentially the spread of first arrivals — denser taps, denser splash. Mutable
	 * (read at argument-build time) so density can be tuned by ear; the kernel cost of
	 * the tap-routing expression grows with {@code taps^2}, which bounds how high this
	 * should go (128 lines would unroll a 16k-term expression per output sample).
	 */
	public static int reverbTaps = 32;

	/**
	 * Longest reverb tap in seconds — the top of the Java {@link DelayNetwork} per-line
	 * delay range (its lines draw uniformly from 0.15–1.5 s). Seconds-denominated so the
	 * reverb room no longer changes size with the buffer: the former frames-denominated
	 * ring halved the room when production moved from 8192 to 4096.
	 */
	private static final double REVERB_TAIL_SECONDS = 1.5;

	/** Shortest reverb tap in seconds — the bottom of the Java per-line delay range. */
	private static final double REVERB_MIN_TAP_SECONDS = 0.15;

	/**
	 * Longest efx feedback delay the ring must accommodate, in beats. Matches the ceiling of
	 * {@code EfxManager}'s delay-time choice gene (its choices top out at {@code 1.5 × 2^2 = 6}
	 * beats), so any gene-selected delay fits within the sized feedback ring.
	 */
	private static final double MAX_FEEDBACK_DELAY_BEATS = 6.0;

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
	 * Builds the {@code [reverbTaps]} per-tap reverb delay lengths, spread over the Java
	 * {@link DelayNetwork}'s 0.15–1.5 s per-line range. The spread uses the golden-ratio
	 * (Weyl) sequence {@code frac((k + 1) * phi^-1)} — quasi-random spacing that avoids
	 * both the metallic comb of uniform taps and the run-to-run nondeterminism of the
	 * Java network's {@code Math.random()} draw, so renders stay reproducible. The lower
	 * bound is floored at one frame ({@code delay_network} is read-first, so sub-frame
	 * taps cannot be represented — they formerly read a full ring-lap stale and spliced
	 * every buffer, the defect this rewrite closed).
	 *
	 * @param config structural configuration
	 * @return a {@code [reverbTaps]} {@link PackedCollection} of per-tap delay sample counts
	 */
	public static PackedCollection reverbTapDelays(Config config) {
		double lo = Math.max(REVERB_MIN_TAP_SECONDS * config.sampleRate, config.signalSize);
		double hi = REVERB_TAIL_SECONDS * config.sampleRate;
		double phiInverse = 2.0 / (1.0 + Math.sqrt(5.0));
		CollectionProducer fraction = ADAPTER.mod(
				ADAPTER.integers(1, reverbTaps + 1).multiply(phiInverse), ADAPTER.c(1.0));
		PackedCollection delays = new PackedCollection(reverbTaps);
		ADAPTER.a(reverbTaps, ADAPTER.cp(delays),
				ADAPTER.floor(fraction.multiply(hi - lo).add(lo))).get().run();
		return delays;
	}

	/**
	 * Number of whole frames the per-tap reverb ring must span so the longest tap
	 * ({@link #REVERB_TAIL_SECONDS}) sits inside the read-first band
	 * {@code [signalSize, ringSize]}.
	 *
	 * @param config structural configuration
	 * @return the ring depth in frames
	 */
	public static int reverbRingFrames(Config config) {
		int maxTap = (int) Math.ceil(REVERB_TAIL_SECONDS * config.sampleRate);
		return (maxTap + config.signalSize - 1) / config.signalSize;
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
	public static PackedCollection householderMatrix(int n, double gain) {
		// gain * (I - off), with off = 2/n applied to every element:
		// diagonal = gain*(1 - off), off-diagonal = -gain*off.
		double off = 2.0 / n;
		PackedCollection matrix = new PackedCollection(new TraversalPolicy(n, n));
		ADAPTER.a(n * n, ADAPTER.cp(matrix),
				ADAPTER.identity(n).multiply(gain).subtract(gain * off)).get().run();
		return matrix;
	}

	/**
	 * Builds the {@code [channels, fir_taps]} per-channel efx filter coefficient bank, matching
	 * {@link EfxManager#applyFilter}: the decision gene ({@code delayLevels[ch,2]}) selects
	 * high-pass vs low-pass and the cutoff gene ({@code 20000 * delayLevels[ch,3]}, clamped to a
	 * valid Nyquist range) sets the corner, over the same windowed-sinc coefficient family.
	 *
	 * <p>The legacy {@code choice(2, …, decision, concat(hp, lp))} selector indexes the
	 * coefficient pair by {@code floor(decision × 2)} — high-pass for {@code decision < 0.5},
	 * low-pass otherwise. {@code Choice} cannot be code-generated inside the compiled PDSL model,
	 * so the selection is reproduced here with generatable arithmetic that compiles into the
	 * model: a {@code sel ∈ {0, 1}} weight blends the two coefficient banks
	 * ({@code hp·(1 − sel) + lp·sel}), yielding exactly the chosen filter per channel.</p>
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
			int src = config.channel(ch);
			// sel = floor(decision * 2) clamped to {0, 1}: 0 selects high-pass, 1 low-pass,
			// matching choice(2, decision, concat(hp, lp)) in EfxManager.applyFilter.
			Producer<PackedCollection> decision = levels.valueAt(src, 2).getResultant(ADAPTER.c(1.0));
			Producer<PackedCollection> sel = ADAPTER.floor(ADAPTER.multiply(
					ADAPTER.min(decision, ADAPTER.c(0.999999)), ADAPTER.c(2.0)));

			Producer<PackedCollection> cutoffHz = ADAPTER.multiply(
					levels.valueAt(src, 3).getResultant(ADAPTER.c(1.0)), ADAPTER.c(20000.0));
			Producer<PackedCollection> clamped = ADAPTER.max(ADAPTER.c(20.0),
					ADAPTER.min(cutoffHz, ADAPTER.c(0.49 * config.sampleRate)));
			CollectionProducer hp =
					ADAPTER.highPassCoefficients(clamped, config.sampleRate, config.filterOrder);
			CollectionProducer lp =
					ADAPTER.lowPassCoefficients(clamped, config.sampleRate, config.filterOrder);
			perChannel[ch] = hp.multiply(ADAPTER.c(1.0).subtract(sel)).add(lp.multiply(sel));
		}
		return channelBank(perChannel, new TraversalPolicy(config.channels, firTaps));
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
		if (!efx.getWetChannels().contains(channel)) {
			// EfxManager.apply passes non-wet channels through untouched — no echo branch
			// at all — so their wet level is zero, which silences the whole apply chain
			// (filter, automation, echo) for that channel.
			return ADAPTER.c(0.0);
		}
		return efx.getDelayLevels().valueAt(channel, 0).getResultant(ADAPTER.c(1.0));
	}

	/**
	 * Produces the shape-{@code [1]} per-channel wet-in send gain — the clock-automated
	 * {@code wetInSimple} adjustment gene that scales each channel's entry into the
	 * bus-line network, mirroring {@code delayGene} in {@link MixdownManager#createEfx}
	 * (which routes every channel into delay layer 0 at this gain). When
	 * {@link MixdownManager#enableWetInAdjustment} is off, the legacy path uses a
	 * constant {@code 0.2} send instead.
	 *
	 * @param manager the mixdown manager whose wet-in chromosome is read
	 * @param channel the source channel index
	 * @return the wet-in send gain producer
	 */
	private static Producer<PackedCollection> wetInProducer(MixdownManager manager, int channel) {
		if (!MixdownManager.enableWetInAdjustment) {
			return ADAPTER.c(0.2);
		}
		return ADAPTER.toAdjustmentGene(
						manager.getClock(), manager.getSampleRate(), null,
						manager.getWetInSimple(), channel)
				.valueAt(0).getResultant(ADAPTER.c(1.0));
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
		return channelBank(cols, new TraversalPolicy(channels));
	}

	/**
	 * Joins per-channel argument columns into a single bank reshaped to {@code shape}.
	 * With two or more columns the columns are joined with {@code concat}; a single
	 * column cannot use {@code concat} (which requires two or more inputs), so the lone
	 * column is wrapped directly and reshaped. Either way the assembled bank has the
	 * same form, so the single-channel selection from {@link AudioScene#renderChannel}
	 * builds its {@code [1]}/{@code [1, taps]}/{@code [1, 1]} bank without tripping
	 * {@code concat}'s arity floor.
	 *
	 * @param columns the per-channel producer columns (length {@code >= 1})
	 * @param shape   the target shape of the assembled bank
	 * @return the assembled, reshaped bank
	 */
	private static Producer<PackedCollection> channelBank(
			Producer<PackedCollection>[] columns, TraversalPolicy shape) {
		CollectionProducer joined = columns.length == 1
				? ADAPTER.c(columns[0]) : ADAPTER.concat(columns);
		return joined.reshape(shape);
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

	/** Diagnostic accessor for the transmission gene chromosome. */
	public static Chromosome<PackedCollection> transmissionGene(MixdownManager manager) {
		return manager.getTransmission();
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
	 * Builds one half of the wet-filter cascade as a {@code [channels, fir_taps]}
	 * coefficient bank: each channel's row is the truncated impulse response of the
	 * {@link AudioPassFilter} biquad at that channel's gene cutoff, selected from the
	 * {@link #biquadResponseTable} by a device-side gather — the same realisation the
	 * main-bus swept filters use, so the wet bus carries the legacy 12 dB/oct slope
	 * rather than a windowed-sinc brickwall.
	 *
	 * <p>The legacy wet filter ({@link FixedFilterChromosome.FixedFilterGene#valueAt})
	 * is a high-pass <em>then</em> low-pass {@link AudioPassFilter} cascade over gene
	 * slots 0 and 1. The cutoffs are read through
	 * {@link FixedFilterChromosome#highPassFrequency(int)} /
	 * {@link FixedFilterChromosome#lowPassFrequency(int)} and bounded to
	 * {@code [MIN_FREQUENCY, 20000]} exactly as the {@code AudioPassFilter} constructor
	 * bounds its frequency. (The previous rendition read
	 * {@code wetFilter.valueAt(ch).valueAt(1)} — but {@code FixedFilterGene.valueAt}
	 * ignores its position and returns the whole composed filter {@code Factor}, so the
	 * "cutoff" was actually the stateful filter chain applied to a constant: a
	 * meaningless value that clamped near the floor and left the wet bus over-filtered.
	 * It also rendered only a low-pass, dropping the cascade's high-pass half.)</p>
	 *
	 * @param manager the mixdown manager whose wet-filter chromosome is sampled
	 * @param config  structural configuration
	 * @param high    {@code true} for the cascade's high-pass half (gene slot 0);
	 *                {@code false} for the low-pass half (gene slot 1)
	 * @return shape-{@code [channels, fir_taps]} coefficient producer
	 */
	private static Producer<PackedCollection> wetFilterCoefficients(MixdownManager manager,
			Config config, boolean high) {
		final int firTaps = config.filterOrder + 1;
		FixedFilterChromosome wetFilter = manager.getWetFilter();
		PackedCollection table = biquadResponseTable(high, config.sampleRate, firTaps);
		Producer<PackedCollection>[] perChannel = new Producer[config.channels];
		for (int ch = 0; ch < config.channels; ch++) {
			int src = config.channel(ch);
			Producer<PackedCollection> cutoff = ADAPTER.bound(
					high ? wetFilter.highPassFrequency(src) : wetFilter.lowPassFrequency(src),
					AudioPassFilter.MIN_FREQUENCY, 20000);
			perChannel[ch] = tableRow(table, cutoff, FILTER_TABLE_BIN_SCALE, firTaps);
		}
		return channelBank(perChannel, new TraversalPolicy(config.channels, firTaps));
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
	 * <p>The transmission grid is read by bank <em>position</em>, not by
	 * {@link Config#channel(int) selected channel} — mirroring the Java path's
	 * {@code .mself(fi(), transmission, ...)} in {@link MixdownManager#createEfx},
	 * which indexes the feedback grid by the cell's own position rather than its
	 * scene channel. A single-channel selection therefore reads the {@code [0][0]}
	 * routing cell regardless of which scene channel is rendered.</p>
	 *
	 * @param manager the mixdown manager whose transmission chromosome is sampled
	 * @param config  structural configuration
	 * @return shape-{@code [channels, channels]} routing matrix producer
	 */
	private static Producer<PackedCollection> transmissionMatrix(MixdownManager manager, Config config) {
		Chromosome<PackedCollection> chrom = manager.getTransmission();
		return gridProducer(config.channels, (n, m) -> transmissionCell(chrom, config.channels, n, m));
	}

	/**
	 * Produces one cell of the genome transmission matrix: the gene resultant inside the
	 * chromosome's extent, zero beyond it. The chromosome's orientation is the legacy
	 * {@code mself} convention — gene {@code n} holds the weights FROM line {@code n}
	 * INTO each destination {@code m}.
	 *
	 * @param chrom  the transmission chromosome
	 * @param extent the grid dimension the cell is read within
	 * @param n      source line (gene index)
	 * @param m      destination line (factor index)
	 * @return the cell producer
	 */
	private static Producer<PackedCollection> transmissionCell(
			Chromosome<PackedCollection> chrom, int extent, int n, int m) {
		int rows = Math.min(chrom.length(), extent);
		int cols = (n < rows) ? Math.min(chrom.valueAt(n).length(), extent) : 0;
		return m < cols
				? chrom.valueAt(n).valueAt(m).getResultant(ADAPTER.c(1.0))
				: ADAPTER.c(0.0);
	}

	/**
	 * Builds the {@code [channels, channels]} apply-echo feedback grid — DIAGONAL-ONLY:
	 * the per-channel self-feedback gene ({@code delayLevels[ch,1]}, already bounded by
	 * the {@link EfxManager} {@code maxFeedback} transform), indexed by
	 * {@link Config#channel(int) selected scene channel} like the other EfxManager gene
	 * reads. The legacy {@link EfxManager#apply} echo recirculates each channel into
	 * itself only; cross-channel regeneration belongs to the bus-line network
	 * ({@link #busTransmissionMatrix}), which recirculates at the bus-delay period.
	 *
	 * @param efx    the effects manager whose self-feedback genes fill the diagonal
	 * @param config structural configuration
	 * @return shape-{@code [channels, channels]} diagonal echo feedback producer
	 */
	private static Producer<PackedCollection> echoFeedbackMatrix(EfxManager efx, Config config) {
		return gridProducer(config.channels, (n, m) -> n.equals(m)
				? regenerationScaled(efx.getDelayLevels().valueAt(config.channel(n), 1)
						.getResultant(ADAPTER.c(1.0)))
				: ADAPTER.c(0.0));
	}

	/**
	 * Applies the {@link #regenerationGain} diagnostic multiplier to a regeneration
	 * matrix cell; at the faithful {@code 1.0} the producer passes through unwrapped.
	 *
	 * @param value the matrix cell producer
	 * @return the (possibly scaled) cell producer
	 */
	private static Producer<PackedCollection> regenerationScaled(Producer<PackedCollection> value) {
		return regenerationGain == 1.0 ? value
				: ADAPTER.multiply(value, ADAPTER.c(regenerationGain));
	}

	/**
	 * Assembles a square {@code [count, count]} producer from a per-cell supplier.
	 *
	 * @param count grid dimension
	 * @param cell  supplies the producer for the cell at (row, column)
	 * @return the assembled grid producer
	 */
	private static Producer<PackedCollection> gridProducer(int count,
			BiFunction<Integer, Integer, Producer<PackedCollection>> cell) {
		Producer<PackedCollection>[] cells = new Producer[count * count];
		for (int n = 0; n < count; n++) {
			for (int m = 0; m < count; m++) {
				cells[n * count + m] = cell.apply(n, m);
			}
		}
		return channelBank(cells, new TraversalPolicy(count, count));
	}

	/**
	 * Builds the {@code [layers, layers]} bus-line recirculation matrix from the genome
	 * transmission chromosome, UNSCALED — mirroring the raw genes the legacy
	 * {@code .mself(fi(), transmission, …)} runs among its delay layers
	 * ({@link MixdownManager#createEfx}). The legacy chromosome is oriented
	 * {@code [from, into]} (gene {@code n} routes line {@code n}'s output), while the
	 * feedback stage's matrix is {@code [into, from]}, so the grid transposes on read.
	 * Row sums of the raw genes can exceed 1 — legacy regeneration genuinely runs away
	 * on some genomes, and the master clip bounds it there as here.
	 *
	 * @param manager the mixdown manager whose transmission chromosome is sampled
	 * @param layers  bus line count
	 * @return shape-{@code [layers, layers]} recirculation matrix producer
	 */
	private static Producer<PackedCollection> busTransmissionMatrix(MixdownManager manager, int layers) {
		Chromosome<PackedCollection> chrom = manager.getTransmission();
		return gridProducer(layers, (j, m) ->
				regenerationScaled(transmissionCell(chrom, layers, m, j)));
	}

	/**
	 * Builds the {@code [layers, layers]} bus-line output matrix — the diagonal per-line
	 * output level, sourced from the {@code wetOut} gene to mirror
	 * {@code fc(wetOut.valueAt(0))} in {@link MixdownManager#createEfx}: the network
	 * emits {@code wetOut[j]} times line {@code j}'s pure delayed tap. Applied as the
	 * feedback stage's passthrough matrix; lines beyond the gene length read zero.
	 *
	 * @param manager the mixdown manager whose {@code wetOut} chromosome is sampled
	 * @param layers  bus line count
	 * @return shape-{@code [layers, layers]} diagonal output-tap producer
	 */
	private static Producer<PackedCollection> busWetOutMatrix(MixdownManager manager, int layers) {
		Gene<PackedCollection> wetOut = manager.getWetOut().valueAt(0);
		int diag = Math.min(wetOut.length(), layers);
		return gridProducer(layers, (n, m) -> (n.equals(m) && n < diag)
				? wetOut.valueAt(n).getResultant(ADAPTER.c(1.0))
				: ADAPTER.c(0.0));
	}

	/**
	 * Builds the static {@code [channels, layers]} bus send routing: the first column
	 * is all ones and every other column is zero, so {@code route(bus_send)} sums
	 * every channel's wet-in-scaled voicing into the first line and feeds the
	 * remaining lines nothing — exactly the legacy {@code delayGene} routing, where
	 * the later lines receive only the transmission recirculation. The pattern is
	 * produced on the device by one structural assignment —
	 * {@code max(1 - (i mod layers)^2, 0)} over the flat extent — like the other
	 * matrix builders here, never staged in a host array.
	 *
	 * @param channels number of source channels
	 * @param layers   bus line count
	 * @return the {@code [channels, layers]} routing matrix
	 */
	private static PackedCollection busSendMatrix(int channels, int layers) {
		PackedCollection send = new PackedCollection(new TraversalPolicy(channels, layers));
		CollectionProducer column = ADAPTER.mod(
				ADAPTER.integers(0, channels * layers), ADAPTER.c((double) layers));
		ADAPTER.a(channels * layers, ADAPTER.cp(send),
				ADAPTER.max(ADAPTER.c(1.0).subtract(column.multiply(column)),
						ADAPTER.c(0.0))).get().run();
		return send;
	}

	/**
	 * Produces one bus line's BASE delay in samples — the {@code delay} chromosome's
	 * 4–20 s gene ({@link MixdownManager#createEfx}, one gene per line) times the
	 * sample rate, before the cursor-rate modulation. Falls back to
	 * {@link Config#delaySamples} if the chromosome is empty.
	 *
	 * @param manager the mixdown manager whose delay chromosome is read
	 * @param config  structural configuration
	 * @param j       bus line index
	 * @return the shape-{@code [1]} base delay producer (samples)
	 */
	private static Producer<PackedCollection> busLineBaseDelay(MixdownManager manager,
			Config config, int j) {
		Chromosome<PackedCollection> delay = manager.getDelay();
		int genes = delay.length();
		return genes <= 0
				? ADAPTER.c((double) config.delaySamples)
				: ADAPTER.multiply(
						delay.valueAt(j % genes, 0).getResultant(ADAPTER.c(1.0)),
						ADAPTER.c((double) config.sampleRate));
	}

	/**
	 * Produces one bus line's EFFECTIVE delay in samples: the base gene delay divided
	 * by the line's clock-driven cursor rate {@code s(t)} (the {@code delayDynamics}
	 * polycyclic gene), floored at one frame. The legacy
	 * {@link org.almostrealism.graph.AdjustableDelayCell} advances both its cursors at
	 * the rate, so the write-read separation is a fixed timeline distance and the
	 * effective delay is the RATIO {@code gene / s(t)} — bounded and memoryless (it
	 * follows the clock, including section resets), never an accumulating drift. The
	 * rate is floored well above zero so a pathological gene cannot divide the delay
	 * toward infinity; the ring clamp bounds slow-down excursions past the ring.
	 *
	 * @param manager the mixdown manager whose delay and dynamics chromosomes are read
	 * @param config  structural configuration
	 * @param j       bus line index
	 * @return the shape-{@code [1]} effective delay producer (samples)
	 */
	private static Producer<PackedCollection> busLineDelay(MixdownManager manager,
			Config config, int j) {
		Producer<PackedCollection> base = busLineBaseDelay(manager, config, j);
		int dynamics = manager.getDelayDynamicsSimple().length();
		if (dynamics > 0) {
			Producer<PackedCollection> rate = ADAPTER.max(
					ADAPTER.toPolycyclicGene(manager.getClock(), manager.getSampleRate(),
									manager.getDelayDynamicsSimple(), j % dynamics)
							.valueAt(0).getResultant(ADAPTER.c(1.0)),
					ADAPTER.c(0.05));
			base = ADAPTER.divide(base, rate);
		}
		return ADAPTER.floor(ADAPTER.max(base,
				ADAPTER.c((double) config.signalSize)));
	}

	/**
	 * Evaluates a shape-{@code [count]} producer once at argument-build time and returns
	 * the ceiling of its largest element. Used to size ring state from the current
	 * genome's gene-driven delays; the kernels' ring-band clamp bounds any later
	 * live-swap value that exceeds the built ring.
	 *
	 * @param values the producer to evaluate
	 * @param count  the element count
	 * @return the ceiling of the maximum element
	 */
	private static int maxEvaluated(Producer<PackedCollection> values, int count) {
		PackedCollection evaluated = new PackedCollection(count);
		ADAPTER.a(count, ADAPTER.cp(evaluated), values).get().run();
		double max = 0.0;
		for (double v : evaluated.toArray(0, count)) {
			max = Math.max(max, v);
		}
		evaluated.destroy();
		return (int) Math.ceil(max);
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
