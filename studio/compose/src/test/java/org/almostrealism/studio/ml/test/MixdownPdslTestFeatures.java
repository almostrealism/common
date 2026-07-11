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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared synthetic-argument builders for tests that construct the PDSL mixdown layers
 * directly (without a scene): neutral efx/reverb argument maps, identity FIR coefficient
 * banks, and standalone reverb-bus arguments. Used by both the correctness suite
 * ({@link MixdownManagerPdslVerificationTest}) and the performance suite
 * ({@link MixdownLayerPerformanceTest}) so the two exercise identical layer
 * configurations.
 */
interface MixdownPdslTestFeatures {

	/** Sample rate used for layer construction. */
	int SAMPLE_RATE = OutputLine.sampleRate;

	/** FIR filter order used when building PDSL mixdown layers. */
	int PDSL_FILTER_ORDER = 40;

	/** Static wet-bus send level supplied to the mixdown layers. */
	double WET_LEVEL = 0.35;

	/** Per-channel efx delay length in samples (must be below any signal size used). */
	int PDSL_DELAY_SAMPLES = 256;

	/**
	 * Builds the neutral efx/reverb argument entries for {@code mixdown_master_wet}: wet
	 * level 0 (the apply echo contributes nothing, so each voicing reduces to dry), echo
	 * feedback 0 (an open echo would emit its delayed tap exactly once without
	 * recirculating), a one-frame bus-line network with unity send and output taps and
	 * zero recirculation, and a zero reverb send, plus validly-shaped state buffers for
	 * every ring on both voicing arms.
	 *
	 * @param channels   number of channels
	 * @param signalSize samples per channel per forward pass
	 * @return argument entries to merge over the adapter-built map
	 */
	default Map<String, Object> neutralEfxArgs(int channels, int signalSize) {
		int firTaps = PDSL_FILTER_ORDER + 1;
		PackedCollection efxCoeffs = new PackedCollection(new TraversalPolicy(channels, firTaps));
		PackedCollection efxWet = new PackedCollection(new TraversalPolicy(channels));
		PackedCollection efxAuto = onesCollection(channels);

		PackedCollection fbDelay = new PackedCollection(new TraversalPolicy(channels));
		double[] fbDelayData = new double[channels];
		Arrays.fill(fbDelayData, PDSL_DELAY_SAMPLES);
		fbDelay.setMem(fbDelayData);
		PackedCollection fbTransmission = new PackedCollection(new TraversalPolicy(channels, channels));

		// Bus-line network: a square (layers == channels) one-frame network whose send
		// matrix sums every channel into the first line (the production routing shape),
		// with zero recirculation and identity output taps.
		PackedCollection busSend = new PackedCollection(new TraversalPolicy(channels, channels));
		double[] busSendData = new double[channels * channels];
		for (int ch = 0; ch < channels; ch++) busSendData[ch * channels] = 1.0;
		busSend.setMem(busSendData);
		PackedCollection busDelays = new PackedCollection(new TraversalPolicy(channels));
		double[] busDelayData = new double[channels];
		Arrays.fill(busDelayData, signalSize);
		busDelays.setMem(busDelayData);
		PackedCollection busTransmission = new PackedCollection(new TraversalPolicy(channels, channels));
		PackedCollection busWetOut = new PackedCollection(new TraversalPolicy(channels, channels));
		double[] busWetOutData = new double[channels * channels];
		for (int i = 0; i < channels; i++) busWetOutData[i * channels + i] = 1.0;
		busWetOut.setMem(busWetOutData);

		int reverbFrames = 4;
		PackedCollection reverbSend = new PackedCollection(new TraversalPolicy(channels));
		PackedCollection reverbDelays = new PackedCollection(new TraversalPolicy(channels));
		double[] reverbDelayData = new double[channels];
		Arrays.fill(reverbDelayData, signalSize);
		reverbDelays.setMem(reverbDelayData);
		PackedCollection reverbFeedback = new PackedCollection(new TraversalPolicy(channels, channels));
		PackedCollection reverbBuffers = new PackedCollection(channels * reverbFrames * signalSize);
		PackedCollection reverbHeads = new PackedCollection(channels);

		Map<String, Object> neutralEfx = new HashMap<>();
		// Identity coefficients for the wet cascade's high-pass half keep the neutral
		// configuration's wet arm a pure pass-through ahead of the (test-supplied or
		// adapter-built) low-pass bank.
		neutralEfx.put("wet_hp_coeffs", identityFirBank(channels, firTaps));
		neutralEfx.put("efx_filter_coeffs", efxCoeffs);
		neutralEfx.put("efx_wet_level", efxWet);
		neutralEfx.put("efx_automation", efxAuto);
		// The ramp_scale stages interpolate previous -> current; aliasing each _prev
		// slot to the SAME collection as its current slot makes every ramp a constant
		// gain — exactly the stepped behaviour these neutral configurations compare
		// against.
		neutralEfx.put("efx_automation_prev", efxAuto);
		neutralEfx.put("efx_fb_delay", fbDelay);
		neutralEfx.put("efx_fb_transmission", fbTransmission);
		// One echo ring bank per voicing arm (the apply echo runs on MAIN and WET alike).
		neutralEfx.put("fb_buffers", new PackedCollection(channels * signalSize));
		neutralEfx.put("fb_heads", new PackedCollection(channels));
		neutralEfx.put("main_fb_buffers", new PackedCollection(channels * signalSize));
		neutralEfx.put("main_fb_heads", new PackedCollection(channels));
		// Unity wet-in send, aliased _prev for a constant ramp; square one-frame bus.
		PackedCollection wetIn = onesCollection(channels);
		neutralEfx.put("wet_in", wetIn);
		neutralEfx.put("wet_in_prev", wetIn);
		neutralEfx.put("delay_layers", channels);
		neutralEfx.put("bus_send", busSend);
		neutralEfx.put("bus_delay_samples", busDelays);
		neutralEfx.put("bus_transmission", busTransmission);
		neutralEfx.put("bus_wet_out", busWetOut);
		neutralEfx.put("bus_buffers", new PackedCollection(channels * signalSize));
		neutralEfx.put("bus_heads", new PackedCollection(channels));
		// The neutral reverb overlay uses one line per channel, so reverb_taps must be
		// overridden to match — every reverb argument's shape follows the tap count
		// (the adapter's own map uses its production tap count with matching shapes).
		neutralEfx.put("reverb_taps", channels);
		neutralEfx.put("reverb_send", reverbSend);
		neutralEfx.put("reverb_send_prev", reverbSend);
		neutralEfx.put("reverb_delays", reverbDelays);
		neutralEfx.put("reverb_feedback", reverbFeedback);
		neutralEfx.put("reverb_buffers", reverbBuffers);
		neutralEfx.put("reverb_heads", reverbHeads);
		neutralEfx.put("main_arm_gain", 1.0);
		neutralEfx.put("efx_arm_gain", 1.0);
		neutralEfx.put("reverb_arm_gain", 1.0);
		neutralEfx.put("reverb_network_gain", 0.1);
		neutralEfx.put("reverb_tap_mean", 1.0 / channels);
		return neutralEfx;
	}

	/**
	 * Builds the synthetic argument map for a standalone {@code mixdown_reverb_bus} build:
	 * per-tap delays of one frame, a zero feedback matrix, and a ring spanning the given
	 * number of frames.
	 *
	 * @param channels number of delay lines
	 * @param signal   samples per forward pass
	 * @param frames   ring length in frames
	 * @return the argument map
	 */
	default Map<String, Object> reverbBusArgs(int channels, int signal, int frames) {
		Map<String, Object> reverb = new HashMap<>();
		reverb.put("channels", channels);
		reverb.put("signal_size", signal);
		PackedCollection reverbDelays = new PackedCollection(new TraversalPolicy(channels));
		double[] delayData = new double[channels];
		Arrays.fill(delayData, signal);
		reverbDelays.setMem(delayData);
		reverb.put("delay_samples", reverbDelays);
		reverb.put("feedback_matrix", new PackedCollection(
				new TraversalPolicy(channels, channels)).fill(0.0));
		reverb.put("reverb_buffers",
				new PackedCollection(channels * frames * signal).fill(0.0));
		reverb.put("reverb_heads", new PackedCollection(channels).fill(0.0));
		return reverb;
	}

	/**
	 * Returns a collection of the given size with every element set to {@code 1.0}.
	 *
	 * @param size element count
	 * @return a ones-filled collection
	 */
	default PackedCollection onesCollection(int size) {
		PackedCollection ones = new PackedCollection(new TraversalPolicy(size));
		double[] data = new double[size];
		Arrays.fill(data, 1.0);
		ones.setMem(data);
		return ones;
	}

	/**
	 * Returns a {@code [channels, taps]} FIR coefficient bank where every channel holds
	 * an identity (centered delta) response.
	 *
	 * @param channels number of channels
	 * @param taps     coefficients per channel
	 * @return the identity coefficient bank
	 */
	default PackedCollection identityFirBank(int channels, int taps) {
		PackedCollection bank = new PackedCollection(new TraversalPolicy(channels, taps));
		double[] data = new double[channels * taps];
		for (int ch = 0; ch < channels; ch++) {
			data[ch * taps + taps / 2] = 1.0;
		}
		bank.setMem(data);
		return bank;
	}

	/**
	 * Returns a {@code [taps]} identity (centered delta) FIR coefficient vector.
	 *
	 * @param taps coefficient count
	 * @return the identity coefficients
	 */
	default PackedCollection identityFir(int taps) {
		PackedCollection coeffs = new PackedCollection(taps);
		double[] data = new double[taps];
		data[taps / 2] = 1.0;
		coeffs.setMem(data);
		return coeffs;
	}
}
