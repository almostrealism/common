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
		double binScale = (FILTER_TABLE_BINS - 1)
				/ Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
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

			// Bus-delay modulation: the legacy AdjustableDelayCells run their read cursor
			// at the delayDynamics gene's polycyclic rate s(t) (a periodic wobble times a
			// slow accelerando), so their delay time evolves as the integral of (1 - s).
			// This accumulates that integral one buffer at a time — an Euler step of
			// (1 - s(clock)) * signalSize per refresh into the bus_delay_drift slot the
			// delay_samples producer reads live. The legacy accelerando (s > 1 growing)
			// makes the wash progressively tighten over an arrangement; the per-buffer
			// step is the block-parallel approximation of that continuous cursor motion
			// (a rate change, i.e. pitch bend, is not representable — the drift changes
			// the delay in whole-sample steps between buffers). Note the drift survives
			// a runner reset by design intent but not by mechanism: the slot is not
			// rewound with the clock, matching a tape delay that keeps its accumulated
			// speed history.
			PackedCollection busDrift = (PackedCollection) args.get("bus_delay_drift");
			int dynamicsLayers = manager.getDelayDynamicsSimple().length();
			if (busDrift != null && dynamicsLayers > 0) {
				for (int ch = 0; ch < config.channels; ch++) {
					Producer<PackedCollection> rate = ADAPTER.toPolycyclicGene(
									manager.getClock(), manager.getSampleRate(),
									manager.getDelayDynamicsSimple(), ch % dynamicsLayers)
							.valueAt(0).getResultant(ADAPTER.c(1.0));
					refresh.add(ADAPTER.a(1, ADAPTER.cp(busDrift.range(ADAPTER.shape(1), ch)),
							ADAPTER.add(ADAPTER.cp(busDrift.range(ADAPTER.shape(1), ch)),
									ADAPTER.multiply(
											ADAPTER.subtract(ADAPTER.c(1.0), rate),
											ADAPTER.c((double) config.signalSize)))));
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

		// delay_samples / buffers: the wet layer's per-channel feedforward delay renders
		// the legacy mixdown-bus delay layer — createEfx's AdjustableDelayCells driven by
		// the `delay` chromosome (4–20 s genes) — replacing the base map's static
		// wire-first constant, which arrived ~30× too early and erased the slow-building
		// wash character of the legacy efx bus. The bus_delay_drift slot carries the
		// accumulated delay-time modulation (the legacy cells' gene-driven cursor-rate
		// wobble and accelerando, integrated per buffer by automationRefresh); the
		// producer folds it into each position's gene delay live. The ring is sized from
		// the current genome's largest evaluated delay plus two frames of wobble
		// headroom; the kernel's ring-band clamp bounds anything beyond it.
		PackedCollection busDrift = new PackedCollection(config.channels).fill(0.0);
		args.put("bus_delay_drift", busDrift);
		Producer<PackedCollection> busDelays = busDelaySamples(manager, config, busDrift);
		int busFrames = (maxEvaluated(busDelays, config.channels)
				+ 3 * config.signalSize - 1) / config.signalSize;
		((PackedCollection) args.get("buffers")).destroy();
		args.put("buffers", new PackedCollection(
				config.channels * busFrames * config.signalSize).fill(0.0));
		args.put("delay_samples", busDelays);

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

		// Recursive feedback grid — the PDSL analogue of the two legacy regeneration
		// loops (EfxManager.apply's per-channel self-echo and MixdownManager.createEfx's
		// .mself(fi(), transmission, fc(wetOut)) bus grid), merged into one block-parallel
		// stage that approximates the per-sample Java recurrences.
		//
		// efx_fb_delay: producer([channels]) — per-channel feedback delay in samples from the
		// EfxManager delay-time gene (delayTimes[ch,0] beat-multiple × beatDuration × sampleRate),
		// mirroring the AdjustableDelayCell delay in EfxManager.apply. Floored to whole samples for
		// an integer ring index; the ring below is sized so every gene delay fits.
		double beatSamples = efx.getBeatDuration() * efx.getSampleRate();
		args.put("efx_fb_delay", perChannelProducer(config.channels, ch -> ADAPTER.floor(
				ADAPTER.multiply(efx.getDelayTimes().valueAt(config.channel(ch), 0)
						.getResultant(ADAPTER.c(1.0)), ADAPTER.c(beatSamples)))));

		// efx_fb_transmission: producer([channels, channels]). The DIAGONAL carries the
		// per-channel self-feedback gene (delayLevels[ch,1], already bounded by the
		// EfxManager maxFeedback transform) — the regeneration control of the legacy
		// EfxManager.apply echo loop, previously read nowhere on the PDSL path, so every
		// channel's echo decayed at the flat contraction rate instead of at its gene's
		// rate. The OFF-DIAGONAL keeps the genome routing matrix scaled by
		// feedbackGain/channels (a contraction, so cross-channel regeneration stays
		// stable). Worst-case row sum is the maxFeedback diagonal cap plus the scaled
		// off-diagonal budget — at saturated genes ~1.0, marginal in theory but well
		// under 1 for real genomes; the wetOut passthrough sits outside the loop and
		// does not affect stability.
		args.put("efx_fb_transmission", feedbackGridMatrix(manager, efx, config));

		// efx_fb_passthrough: producer([channels, channels]) — the per-position feedback output
		// level on the diagonal, sourced from the wetOut gene (mirroring fc(wetOut.valueAt(0)) in
		// MixdownManager.createEfx). Full gene value, no stability cap (owner: maximum fidelity).
		args.put("efx_fb_passthrough", passthroughMatrix(manager, config));

		// Fresh feedback ring state. The ring spans whole frames (feedbackNetworkBlock requires
		// the per-channel buffer to be a multiple of signal_size) sized so the longest gene delay
		// fits: ceil((maxDelay + 1) / signal_size) frames, where maxDelay covers the gene's delay
		// ceiling (MAX_FEEDBACK_DELAY_BEATS). One write head per channel.
		int maxDelaySamples = (int) Math.ceil(MAX_FEEDBACK_DELAY_BEATS * beatSamples);
		int fbFrames = Math.max(1, (maxDelaySamples + 1 + config.signalSize - 1) / config.signalSize);
		int fbRing = fbFrames * config.signalSize;
		PackedCollection fbBuffers = new PackedCollection(config.channels * fbRing).fill(0.0);
		PackedCollection fbHeads = new PackedCollection(config.channels).fill(0.0);
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
			Producer<PackedCollection> lpUnit = wetFilter.valueAt(config.channel(ch)).valueAt(1)
					.getResultant(ADAPTER.c(1.0));
			Producer<PackedCollection> lpHz = ADAPTER.multiply(lpUnit, ADAPTER.c(20000.0));
			Producer<PackedCollection> lpHzClamped = ADAPTER.max(ADAPTER.c(20.0),
					ADAPTER.min(lpHz, ADAPTER.c(0.49 * config.sampleRate)));
			perChannel[ch] = ADAPTER.lowPassCoefficients(
					lpHzClamped, config.sampleRate, config.filterOrder);
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
		return gridProducer(config, (n, m) -> transmissionCell(chrom, config, n, m));
	}

	/**
	 * Produces one cell of the genome transmission matrix: the gene resultant inside the
	 * chromosome's extent, zero beyond it.
	 *
	 * @param chrom  the transmission chromosome
	 * @param config structural configuration
	 * @param n      row (source bank position)
	 * @param m      column (destination bank position)
	 * @return the cell producer
	 */
	private static Producer<PackedCollection> transmissionCell(
			Chromosome<PackedCollection> chrom, Config config, int n, int m) {
		int rows = Math.min(chrom.length(), config.channels);
		int cols = (n < rows) ? Math.min(chrom.valueAt(n).length(), config.channels) : 0;
		return m < cols
				? chrom.valueAt(n).valueAt(m).getResultant(ADAPTER.c(1.0))
				: ADAPTER.c(0.0);
	}

	/**
	 * Builds the {@code [channels, channels]} feedback transmission grid. The
	 * <b>diagonal</b> carries the per-channel self-feedback gene
	 * ({@code delayLevels[ch,1]}, already bounded by the {@link EfxManager}
	 * {@code maxFeedback} transform) — the regeneration control of the legacy
	 * {@link EfxManager#apply} echo loop, indexed by
	 * {@link Config#channel(int) selected scene channel} like the other EfxManager gene
	 * reads. The <b>off-diagonal</b> cells are the genome transmission matrix scaled by
	 * {@code feedbackGain / channels}, position-indexed like {@link #transmissionMatrix},
	 * so cross-channel regeneration remains a stable contraction. (The genome
	 * transmission's own diagonal entries are superseded by the self-feedback gene.)
	 *
	 * @param manager the mixdown manager whose transmission chromosome is sampled
	 * @param efx     the effects manager whose self-feedback genes fill the diagonal
	 * @param config  structural configuration
	 * @return shape-{@code [channels, channels]} feedback grid producer
	 */
	private static Producer<PackedCollection> feedbackGridMatrix(MixdownManager manager,
			EfxManager efx, Config config) {
		Chromosome<PackedCollection> chrom = manager.getTransmission();
		return gridProducer(config, (n, m) -> n.equals(m)
				? efx.getDelayLevels().valueAt(config.channel(n), 1)
						.getResultant(ADAPTER.c(1.0))
				: ADAPTER.multiply(transmissionCell(chrom, config, n, m),
						ADAPTER.c(feedbackGain / config.channels)));
	}

	/**
	 * Assembles a {@code [channels, channels]} producer from a per-cell supplier.
	 *
	 * @param config structural configuration
	 * @param cell   supplies the producer for the cell at (row, column)
	 * @return the assembled grid producer
	 */
	private static Producer<PackedCollection> gridProducer(Config config,
			BiFunction<Integer, Integer, Producer<PackedCollection>> cell) {
		Producer<PackedCollection>[] cells = new Producer[config.channels * config.channels];
		for (int n = 0; n < config.channels; n++) {
			for (int m = 0; m < config.channels; m++) {
				cells[n * config.channels + m] = cell.apply(n, m);
			}
		}
		return channelBank(cells, new TraversalPolicy(config.channels, config.channels));
	}

	/**
	 * Builds the {@code [channels, channels]} feedback passthrough matrix — the diagonal output
	 * level of the efx feedback grid, sourced from the {@code wetOut} gene to mirror
	 * {@code fc(wetOut.valueAt(0))} in {@link MixdownManager#createEfx}. The single {@code wetOut}
	 * gene's per-position factors fill the diagonal (bank position {@code n} reads factor {@code n},
	 * matching {@link #transmissionMatrix}'s position-indexed convention); off-diagonal cells are
	 * zero. Positions beyond the gene length read zero.
	 *
	 * @param manager the mixdown manager whose {@code wetOut} chromosome is sampled
	 * @param config  structural configuration
	 * @return shape-{@code [channels, channels]} diagonal passthrough producer
	 */
	private static Producer<PackedCollection> passthroughMatrix(MixdownManager manager, Config config) {
		Gene<PackedCollection> wetOut = manager.getWetOut().valueAt(0);
		int diag = Math.min(wetOut.length(), config.channels);
		return gridProducer(config, (n, m) -> (n.equals(m) && n < diag)
				? wetOut.valueAt(n).getResultant(ADAPTER.c(1.0))
				: ADAPTER.c(0.0));
	}

	/**
	 * Builds the {@code [channels]} per-position feedforward delay for the wet arm — the
	 * PDSL rendition of the legacy mixdown-bus delay layer, whose
	 * {@link org.almostrealism.graph.AdjustableDelayCell} lengths come from the
	 * {@code delay} chromosome (4–20 s genes) in {@link MixdownManager#createEfx}. Bank
	 * positions cycle across the {@code delayLayers} genes (position-indexed, like the
	 * transmission grid, since the legacy fan of N channels into M delay layers has no
	 * per-channel identity to preserve). The {@code drift} slot's accumulated
	 * modulation offset (see {@link #automationRefresh}) is added live and the result
	 * floored at one frame — the smallest delay the block-parallel stage renders — so
	 * the legacy accelerando can tighten the wash without collapsing it into the dry
	 * signal. Falls back to {@link Config#delaySamples} if the chromosome is empty.
	 *
	 * @param manager the mixdown manager whose delay chromosome is read
	 * @param config  structural configuration
	 * @param drift   shape-{@code [channels]} accumulated delay-modulation offsets
	 * @return shape-{@code [channels]} delay-length producer (samples)
	 */
	private static Producer<PackedCollection> busDelaySamples(MixdownManager manager, Config config,
			PackedCollection drift) {
		Chromosome<PackedCollection> delay = manager.getDelay();
		int layers = delay.length();
		return perChannelProducer(config.channels, pos -> {
			Producer<PackedCollection> base = layers <= 0
					? ADAPTER.c((double) config.delaySamples)
					: ADAPTER.multiply(
							delay.valueAt(pos % layers, 0).getResultant(ADAPTER.c(1.0)),
							ADAPTER.c((double) config.sampleRate));
			return ADAPTER.floor(ADAPTER.max(
					ADAPTER.add(base, ADAPTER.cp(drift.range(ADAPTER.shape(1), pos))),
					ADAPTER.c((double) config.signalSize)));
		});
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
