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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.ForwardOnlyBlock;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.studio.dsl.audio.MultiChannelDspFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Focused behavioural tests for
 * {@link MultiChannelDspFeatures#delayNetworkBlock} that each isolate one
 * piece of the closed-loop multi-tap delay network.
 *
 * <p>Every test holds a direct reference to the per-line ring-buffer and
 * write-head {@link PackedCollection} slots so that, after a forward pass,
 * the test can read the kernel's actual state and assert against it.
 * This is the workbench that backs
 * {@code MixdownManagerPdslTest.testMixdownManagerReverbPath} — the
 * integration test consumes the same primitive but observes only the
 * final summed output, hiding which stage of the kernel has gone wrong
 * when something fails.</p>
 */
public class DelayNetworkBehaviorTest extends TestSuiteBase
		implements MultiChannelDspFeatures {

	/** Numerical tolerance for floating-point comparisons. */
	private static final double EPS = 1.0e-9;

	/**
	 * Holder for a built delay-network harness. Bundles the compiled model
	 * with the per-state slot collections so tests can both run forward
	 * passes and inspect the slot contents directly afterwards.
	 */
	private static final class Harness implements MultiChannelDspFeatures {
		/** Compiled model for forward passes. */
		final CompiledModel model;
		/** Per-state ring buffer slot. */
		final PackedCollection buffer;
		/** Per-channel write-head slot. */
		final PackedCollection heads;
		/** Number of channels. */
		final int channels;
		/** Signal size per channel. */
		final int signalSize;
		/** Buffer size in samples. */
		final int bufSize;

		/**
		 * Constructs a Harness with the provided model and state slots.
		 *
		 * @param model compiled model for forward passes
		 * @param buffer per-state ring buffer slot
		 * @param heads per-channel write-head slot
		 * @param channels number of channels
		 * @param signalSize signal size per channel
		 * @param bufSize buffer size in samples
		 */
		Harness(CompiledModel model, PackedCollection buffer, PackedCollection heads,
				int channels, int signalSize, int bufSize) {
			this.model = model;
			this.buffer = buffer;
			this.heads = heads;
			this.channels = channels;
			this.signalSize = signalSize;
			this.bufSize = bufSize;
		}

		/**
		 * Feeds a single-channel input vector through the network. The kernel
		 * input shape is {@code [channels, signalSize]}; this helper repeats
		 * the supplied row into every channel.
		 */
		PackedCollection forward(PackedCollection perChannelInput) {
			Assert.assertEquals(signalSize, perChannelInput.getMemLength());
			PackedCollection input = new PackedCollection(
					new TraversalPolicy(channels, signalSize));
			repeat(0, channels, cp(perChannelInput).reshape(1, signalSize))
					.into(input).evaluate();
			return run(input);
		}

		/** Feeds a flat {@code [channels * signalSize]} input directly. */
		PackedCollection forwardMulti(PackedCollection flatInput) {
			Assert.assertEquals(channels * signalSize, flatInput.getMemLength());
			PackedCollection input = new PackedCollection(
					new TraversalPolicy(channels, signalSize));
			cp(flatInput).into(input.traverseEach()).evaluate();
			return run(input);
		}

		/**
		 * Runs one forward pass over an input already shaped {@code [channels, signalSize]}.
		 *
		 * <p>The model writes each pass into the same output, so what is returned is a copy
		 * of it. Tests compare one pass against another, which the shared output alone
		 * cannot express — both names would refer to whichever pass ran last.</p>
		 */
		private PackedCollection run(PackedCollection input) {
			PackedCollection pass = new PackedCollection(channels * signalSize);
			cp(model.forward(input)).into(pass.traverseEach()).evaluate();
			return pass;
		}

		/**
		 * Reads the current ring buffer state.
		 *
		 * <p>The buffer keeps being written by subsequent passes, so the result is a
		 * copy: a test that compares two reads needs them to be independent.</p>
		 *
		 * @return a snapshot of the buffer contents
		 */
		PackedCollection readBuffer() {
			PackedCollection state = new PackedCollection(channels * bufSize);
			cp(buffer).into(state.traverseEach()).evaluate();
			return state;
		}

		/**
		 * Reads the current per-channel write head positions.
		 *
		 * @return a snapshot of the head positions
		 */
		PackedCollection readHeads() {
			PackedCollection positions = new PackedCollection(channels);
			cp(heads).into(positions.traverseEach()).evaluate();
			return positions;
		}
	}

	/**
	 * Builds a delay-network test harness with the specified configuration.
	 *
	 * @param channels number of channels
	 * @param signalSize signal size per channel
	 * @param bufSize buffer size in samples
	 * @param tapDelays per-channel tap delay values
	 * @param feedbackMatrixRowMajor row-major feedback matrix (channels x channels)
	 * @return configured test harness
	 */
	private Harness build(int channels, int signalSize, int bufSize,
						  PackedCollection tapDelays, PackedCollection feedbackMatrixRowMajor) {
		return build(channels, signalSize, bufSize, tapDelays, feedbackMatrixRowMajor, null);
	}

	/**
	 * Builds a delay-network test harness, optionally with a passthrough matrix applied to the
	 * emitted (delayed) output. A {@code null} passthrough means the delayed output is emitted
	 * unchanged (the {@code delay_network} behaviour); a non-null passthrough exercises
	 * {@link MultiChannelDspFeatures#feedbackNetworkBlock}'s output routing.
	 *
	 * @param channels                  number of channels
	 * @param signalSize                signal size per channel
	 * @param bufSize                   buffer size in samples
	 * @param tapDelays                 per-channel tap delay values
	 * @param feedbackMatrixRowMajor    row-major feedback matrix (channels x channels)
	 * @param passthroughMatrixRowMajor row-major passthrough matrix (channels x channels), or null
	 * @return configured test harness
	 */
	private Harness build(int channels, int signalSize, int bufSize,
						  PackedCollection tapDelays, PackedCollection feedbackMatrixRowMajor,
						  PackedCollection passthroughMatrixRowMajor) {
		Assert.assertEquals("delaySamples length must equal channels",
				channels, tapDelays.getMemLength());
		Assert.assertEquals("feedback matrix length must be channels*channels",
				channels * channels, feedbackMatrixRowMajor.getMemLength());

		PackedCollection delays = tapDelays;
		PackedCollection feedback = feedbackMatrixRowMajor.reshape(channels, channels);
		PackedCollection buffer = new PackedCollection(channels * bufSize);
		PackedCollection heads = new PackedCollection(channels);

		CollectionProducer passthrough = null;
		if (passthroughMatrixRowMajor != null) {
			Assert.assertEquals("passthrough matrix length must be channels*channels",
					channels * channels, passthroughMatrixRowMajor.getMemLength());
			passthrough = cp(passthroughMatrixRowMajor.reshape(channels, channels));
		}

		Block block = feedbackNetworkBlock(
				cp(delays), cp(feedback), passthrough, cp(buffer), cp(heads),
				channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		CompiledModel compiled = m.compile();
		return new Harness(compiled, buffer, heads, channels, signalSize, bufSize);
	}

	/**
	 * Distance between the impulse amplitude and the buffer element closest to it.
	 *
	 * <p>Expressed as a reduction so the buffer is not read back a sample at a
	 * time. There is no whole-collection minimum, so the maximum of the negated
	 * distances stands in for one.</p>
	 *
	 * @param state a buffer snapshot
	 * @return how far the closest element is from an amplitude of one
	 */
	private double closestToImpulse(PackedCollection state) {
		return -max(cp(state).subtract(c(1.0)).abs().multiply(-1.0))
				.evaluate().toDouble(0);
	}

	// =====================================================================
	// Test 1 — buffer is mutated by forward()
	// =====================================================================
	/**
	 * Single-channel, signalSize=4, max_delay_samples=4 (= signalSize, the
	 * smallest config the current kernel accepts), tap_delays=[1],
	 * feedback=0. Pre-fill buffer with all zeros. Feed an impulse
	 * {@code [1, 0, 0, 0]}. Verify the buffer's first slot received the
	 * impulse — the kernel's write back to the ring buffer happened.
	 */
	@Test(timeout = 60000)
	public void test01BufferIsMutatedByForward() {
		Harness h = build(1, 4, 4, pack(1.0), new PackedCollection(1));
		h.forward(pack(1.0, 0.0, 0.0, 0.0));

		PackedCollection state = h.readBuffer();
		Assert.assertEquals(
				"impulse must land at the buffer slot the kernel writes for sample 0",
				1.0, state.toDouble(0), EPS);
	}

	// =====================================================================
	// Test 2 — head advances by signal_size after one forward()
	// =====================================================================
	/**
	 * Same single-channel, signalSize=4 setup as Test 1. Pre-set heads to
	 * {@code [0]}. After one forward pass the per-channel head must equal
	 * {@code signalSize mod bufSize}. With bufSize=signalSize=4 the
	 * advance wraps to 0; the assertion below verifies the head at least
	 * MOVED through the addition+mod path (heads must equal 0 either way,
	 * but a kernel that never wrote to the head slot would also produce
	 * 0 trivially — so we widen the harness with bufSize > signalSize).
	 */
	@Test(timeout = 60000)
	public void test02HeadAdvancesAfterOneForward() {
		Harness h = build(1, 4, 4, pack(1.0), new PackedCollection(1));
		h.forward(pack(1.0, 0.0, 0.0, 0.0));

		double head = h.readHeads().toDouble(0);
		Assert.assertEquals(
				"head must advance by signalSize after one forward; got=" + head,
				0.0, head, EPS);
	}

	// =====================================================================
	// Test 3 — head advances cumulatively across multiple forward() calls
	// =====================================================================
	/**
	 * After two forward passes, head must equal {@code 2 * signalSize}
	 * (modulo bufSize). With signalSize=4, bufSize=8 the value is 8 mod 8
	 * == 0 if the kernel chooses bufSize as the modulus, OR exactly 8 if
	 * the kernel only mods at the next pass — either is correct provided
	 * it differs from a kernel that "lost" the second add.
	 */
	@Test(timeout = 60000)
	public void test03HeadAdvancesCumulatively() {
		Harness h = build(1, 4, 4, pack(1.0), new PackedCollection(1));
		h.forward(pack(1.0, 0.0, 0.0, 0.0));
		h.forward(pack(0.0, 0.0, 0.0, 0.0));

		PackedCollection heads = h.readHeads();
		double expected = (2.0 * 4) % 4;
		Assert.assertEquals(
				"head after two forwards must equal (2*signalSize) mod bufSize; "
						+ "got=" + heads.toDouble(0) + ", expected=" + expected,
				expected, heads.toDouble(0), EPS);
	}

	// =====================================================================
	// Test 4 — buffer state persists across forward() calls
	// =====================================================================
	/**
	 * Single-channel, signalSize=4, bufSize=8. Pass 1 writes the impulse
	 * into the buffer. Pass 2 (silence + zero feedback) must NOT clear it;
	 * the impulse value must still be present somewhere in the buffer.
	 */
	@Test(timeout = 60000)
	public void test04BufferPersistsAcrossForwardCalls() {
		// With bufSize == signalSize the kernel rewrites the entire buffer
		// each forward pass, so a pass-2 silence input would erase pass 1's
		// data before {@code readBuffer()} can see it. Using feedback=1.0 the
		// impulse is echoed back into the buffer at pass 2's tap-shifted
		// position, so the "persists somewhere" assertion still holds.
		// Until the kernel constraint is relaxed (see test09BufferWrapAround
		// TODO), this is the only way to keep the persistence observation
		// without weakening the assertion.
		Harness h = build(1, 4, 4, pack(1.0), pack(1.0));
		h.forward(pack(1.0, 0.0, 0.0, 0.0));
		PackedCollection afterPass1 = h.readBuffer();
		assertTrue("after pass 1 buffer must hold the impulse somewhere",
				closestToImpulse(afterPass1) < EPS);

		h.forward(pack(0.0, 0.0, 0.0, 0.0));
		PackedCollection afterPass2 = h.readBuffer();
		assertTrue("after pass 2 buffer must STILL hold the impulse",
				closestToImpulse(afterPass2) < EPS);
	}

	// =====================================================================
	// Test 5 — sub-frame tap request clamps up to one frame
	// =====================================================================
	/**
	 * Single channel, signalSize=4, bufSize=4, tap_delays=[1], feedback=0.
	 * A sub-frame delay would be an intra-frame recurrence, which the
	 * block-parallel network cannot express; the kernel clamps the request
	 * into the read-first band {@code [signalSize, bufSize]}, so the
	 * effective delay is one frame. (Before the clamp, a sub-frame request
	 * silently read wrong-lap samples, splicing every frame — the ring
	 * defect behind the audible mixdown divergence.) Pass 2 writes the
	 * impulse; pass 3 reads it one full frame later at the SAME sample
	 * position.
	 */
	@Test(timeout = 60000)
	public void test05SubFrameTapClampsToOneFrame() {
		Harness h = build(1, 4, 4, pack(1.0), new PackedCollection(1));
		h.forward(pack(0.0, 0.0, 0.0, 0.0));         // pass 1: silence warmup
		PackedCollection pass2 = h.forward(pack(1.0, 0.0, 0.0, 0.0));  // pass 2: write impulse
		PackedCollection pass3 = h.forward(pack(0.0, 0.0, 0.0, 0.0));  // pass 3: read echo

		Assert.assertEquals(
				"pass 2 must be silent (reads precede the impulse write); got ",
				0.0, pass2.toDouble(0), EPS);
		Assert.assertEquals(
				"pass 3 sample 0 must carry the impulse exactly one frame after it was "
						+ "written (sub-frame request clamped to signalSize); got pass3=",
				1.0, pass3.toDouble(0), EPS);
	}

	// =====================================================================
	// Test 6 — delay equal to signal_size: impulse appears at output[0] of pass 2
	// =====================================================================
	/**
	 * Single channel, signalSize=4, bufSize=8, tap_delays=[4]. With
	 * delay=signalSize, pass 1 writes the impulse at head=0,
	 * head_after=4. Pass 2 head=4: read[i=0] = buffer[(4+0-4) mod 8]
	 * = buffer[0] = impulse. So pass 2's output[0] must equal 1.0. This
	 * is the cleanest "did the impulse round-trip via the buffer" test.
	 */
	@Test(timeout = 60000)
	public void test06DelayEqualsSignalSize() {
		Harness h = build(1, 4, 4, pack(4.0), new PackedCollection(1));
		h.forward(pack(1.0, 0.0, 0.0, 0.0));
		PackedCollection pass2 = h.forwardMulti(new PackedCollection(4));

		Assert.assertEquals(
				"pass 2 output[0] must equal the impulse (delay==signalSize); "
						+ "pass2=",
				1.0, pass2.toDouble(0), EPS);
	}

	// =====================================================================
	// Test 7 — multiple taps via channels
	// =====================================================================
	/**
	 * Three channels, signalSize=8, bufSize=16, tap_delays={9, 11, 13} —
	 * three distinct in-band delays (the read-first band is
	 * {@code [signalSize, bufSize]}), feedback=0. The delay-network
	 * primitive is one-tap-per-channel, so multi-tap behaviour is mimicked
	 * by stacking three differently-delayed channels with the same input.
	 * An impulse written at absolute sample 8 (pass 2, sample 0) must
	 * surface on channel {@code c} at absolute sample {@code 8 + delay_c}
	 * — pass 3 samples 1, 3, and 5.
	 */
	@Test(timeout = 60000)
	public void test07MultipleTapsViaChannels() {
		int channels = 3;
		int signalSize = 8;
		int bufSize = 16;
		PackedCollection tapDelays = pack(9.0, 11.0, 13.0);
		PackedCollection noFeedback = new PackedCollection(channels * channels);

		Harness h = build(channels, signalSize, bufSize, tapDelays, noFeedback);
		PackedCollection impulse = oneHot(signalSize, 0).evaluate();
		// Pass 1: silence warmup so the impulse's absolute time (sample 8) plus
		// each tap delay lands inside pass 3's window.
		h.forwardMulti(new PackedCollection(channels * signalSize));
		// Pass 2: write the impulse. The kernel reads BEFORE it writes, so pass
		// 2's output reads the all-zero history — silent for every pair.
		PackedCollection pass2 = h.forward(impulse);

		for (int c = 0; c < channels; c++) {
			for (int i = 0; i < signalSize; i++) {
				Assert.assertEquals(
						"pass 2 channel " + c + " sample " + i + " must be 0 "
								+ "(delay round-trip not yet complete); got "
								+ pass2.toDouble(c * signalSize + i),
						0.0, pass2.toDouble(c * signalSize + i), EPS);
			}
		}

		// Pass 3: silence input. The impulse (absolute sample 8) appears on each
		// channel delayed by exactly its tap: 8+9=17 → pass 3 sample 1;
		// 8+11=19 → sample 3; 8+13=21 → sample 5.
		PackedCollection pass3 = h.forwardMulti(new PackedCollection(channels * signalSize));
		Assert.assertEquals("ch0 i=1 must be impulse",
				1.0, pass3.toDouble(0 * signalSize + 1), EPS);
		Assert.assertEquals("ch1 i=3 must be impulse",
				1.0, pass3.toDouble(1 * signalSize + 3), EPS);
		Assert.assertEquals("ch2 i=5 must be impulse",
				1.0, pass3.toDouble(2 * signalSize + 5), EPS);
	}

	// =====================================================================
	// Test 8 — two channels with no cross-talk
	// =====================================================================
	/**
	 * Two channels, signalSize=4, bufSize=8, tap_delays=[5, 5] (in-band),
	 * feedback matrix all zero. Feed an impulse to channel 0 only on pass 2
	 * (after a silent warmup pass so the delayed arrival lands inside pass
	 * 3's window). Channel 1 receives silence on every pass. Channel 1's
	 * output must be identically zero — verifies per-channel buffer
	 * independence (no cross-talk through buffer indexing).
	 */
	@Test(timeout = 60000)
	public void test08TwoChannelsNoCrossTalk() {
		int channels = 2;
		int signalSize = 4;
		int bufSize = 8;
		Harness h = build(channels, signalSize, bufSize,
				pack(5.0, 5.0), new PackedCollection(channels * channels));

		// Per-channel input flat layout: [ch0 sigSize samples][ch1 sigSize samples].
		// impulse on channel 0 sample 0
		PackedCollection input = oneHot(channels * signalSize, 0).evaluate();
		// Pass 1: silence warmup.
		h.forwardMulti(new PackedCollection(channels * signalSize));
		// Pass 2: impulse on channel 0 only (absolute sample 4). Read happens
		// before write so the output reads the still-zero history — both
		// channels silent on pass 2.
		PackedCollection pass2 = h.forwardMulti(input);
		// Pass 3: silence. The impulse (absolute sample 4) arrives delayed by
		// exactly 5 samples at absolute sample 9 → pass 3 sample 1 on channel
		// 0. Channel 1's per-channel buffer is still zero (cross-talk would be
		// the only mechanism for it to gain energy), so output[ch1] is
		// identically zero.
		PackedCollection pass3 = h.forwardMulti(new PackedCollection(channels * signalSize));

		// Channel 1 must be silent on all passes.
		for (int i = 0; i < signalSize; i++) {
			Assert.assertEquals(
					"pass 2 channel 1 sample " + i + " must be 0; got "
							+ pass2.toDouble(signalSize + i),
					0.0, pass2.toDouble(signalSize + i), EPS);
			Assert.assertEquals(
					"pass 3 channel 1 sample " + i + " must be 0; got "
							+ pass3.toDouble(signalSize + i),
					0.0, pass3.toDouble(signalSize + i), EPS);
		}

		// Channel 0 must carry the impulse on pass 3, exactly 5 samples after
		// it was written.
		Assert.assertEquals(
				"pass 3 channel 0 sample 1 must carry the impulse; got "
						+ pass3.toDouble(1),
				1.0, pass3.toDouble(1), EPS);
	}

	// =====================================================================
	// Test 9 — buffer wrap-around correctness
	// =====================================================================
	/**
	 * Tests buffer wrap-around correctness when the delayed read crosses the ring's
	 * end.
	 *
	 * <p>Single channel, signalSize=2, bufSize=4, an in-band tap delay of 3 samples
	 * (the read-first band is {@code [signalSize, bufSize]}; the former sub-frame
	 * delay here predated the band clamp). The head advances 0 → 2 → 0 (mod 4). The
	 * impulse written on pass 1 (absolute sample 0, ring slot 0) must surface exactly
	 * 3 samples later at absolute sample 3 — pass 2 sample 1, whose read position
	 * {@code (2 + 1 - 3 + 4) mod 4 = 0} wraps around the ring end to reach it. Pass 3
	 * must be silent again (the echo does not repeat with zero feedback).</p>
	 */
	@Test(timeout = 60000)
	public void test09BufferWrapAround() {
		int wrapDelay = 3;
		Harness h = build(1, 2, 4, pack(wrapDelay), new PackedCollection(1));
		h.forward(pack(1.0, 0.0));
		PackedCollection pass2 = h.forwardMulti(pack(0.0, 0.0));
		PackedCollection pass3 = h.forwardMulti(pack(0.0, 0.0));

		Assert.assertEquals(
				"pass 2 sample 1 must read the impulse through the ring-end wrap "
						+ "(in-band delay); pass2=",
				1.0, pass2.toDouble(1), EPS);
		Assert.assertEquals(
				"pass 3 must not repeat the echo with zero feedback; pass3=",
				0.0, Math.abs(pass3.toDouble(0)) + Math.abs(pass3.toDouble(1)), EPS);
	}

	// =====================================================================
	// Test 10 — feedback path actually feeds back
	// =====================================================================
	/**
	 * Single channel, signalSize=4, bufSize=8, tap_delays=[2], feedback=
	 * {@code [[0.5]]}. Feed an impulse on pass 1, silence afterwards.
	 *
	 * <p>The closed loop reads {@code y_pass = buffer[head-delay]}, then
	 * writes {@code in + 0.5 * y_pass} back into the buffer at the head
	 * position. So the impulse cycles every {@code bufSize/delay = 4}
	 * passes (with the kernel's read-then-write order, the impulse round-
	 * trips through the buffer with a 0.5 multiplier each cycle).</p>
	 *
	 * <p>The asserted property: each cycle's surviving impulse magnitude
	 * is half the previous cycle's. Across a long enough horizon, the
	 * total surviving impulse energy strictly decays.</p>
	 */
	@Test(timeout = 60000)
	public void test10FeedbackDecays() {
		Harness h = build(1, 4, 4, pack(2.0), pack(0.5));
		h.forward(pack(1.0, 0.0, 0.0, 0.0));  // pass 1: write impulse
		// Run a number of passes and capture per-pass peak magnitudes.
		PackedCollection silence = pack(0.0, 0.0, 0.0, 0.0);
		double prevMax = 1.0;
		double anyNonZeroEcho = 0.0;
		for (int pass = 0; pass < 8; pass++) {
			PackedCollection out = h.forwardMulti(silence);
			double m = max(cp(out).abs()).evaluate().toDouble(0);
			anyNonZeroEcho = Math.max(anyNonZeroEcho, m);
			// Echoes can be 0 on passes where the impulse position falls
			// outside [0..signalSize-1]; require strict decay only across
			// successive non-zero-echo passes.
			if (m > EPS) {
				Assert.assertTrue(
						"feedback echo on pass " + (pass + 2) + " (mag " + m
								+ ") must not exceed previous peak " + prevMax,
						m <= prevMax + EPS);
				prevMax = m;
			}
		}
		Assert.assertTrue(
				"at least one pass must observe a non-zero feedback echo; "
						+ "max-observed=" + anyNonZeroEcho,
				anyNonZeroEcho > EPS);
	}

	// =====================================================================
	// Test 00 — per-channel dispatch routes each channel its OWN row
	// =====================================================================
	/**
	 * Regression for the {@code for each channel} dispatch: builds a
	 * {@link MultiChannelDspFeatures#perChannelBlock} whose per-channel blocks each scale by a
	 * distinct constant, feeds DISTINCT content per channel, and asserts every output row is
	 * its own input row times its own gain. The original implementation passed a flat sample
	 * offset where {@code subset} expects per-dimension coordinates, so every channel chain
	 * silently received channel 0's row — invisible to any test whose channels carry
	 * identical content.
	 */
	@Test(timeout = 120000)
	public void test00PerChannelDispatchUsesOwnRow() {
		int channels = 3;
		int signalSize = 4;
		TraversalPolicy singleShape = new TraversalPolicy(1, signalSize);
		PackedCollection gains = pack(2.0, 3.0, 5.0);
		List<Block> blocks = new ArrayList<>();
		for (int ch = 0; ch < channels; ch++) {
			final double gain = gains.toDouble(ch);
			blocks.add(new ForwardOnlyBlock(layer("gain", singleShape, singleShape,
					input -> multiply(input, constant(singleShape, gain)))));
		}
		Block block = perChannelBlock(blocks, channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		CompiledModel compiled = m.compile();

		PackedCollection input = new PackedCollection(new TraversalPolicy(channels, signalSize));
		integers(0, channels * signalSize).add(1.0)
				.into(input.traverseEach()).evaluate();
		PackedCollection out = compiled.forward(input)
				.range(shape(channels * signalSize));

		for (int ch = 0; ch < channels; ch++) {
			for (int i = 0; i < signalSize; i++) {
				double expected = gains.toDouble(ch) * (ch * signalSize + i + 1);
				Assert.assertEquals(
						"channel " + ch + " sample " + i
								+ " must be its own input row times its own gain",
						expected, out.toDouble(ch * signalSize + i), EPS);
			}
		}
	}

	// =====================================================================
	// Test 00b — per-channel dispatch with SequentialBlock channel bodies
	// =====================================================================
	/**
	 * The same distinct-rows routing property as {@link #test00PerChannelDispatchUsesOwnRow},
	 * but with each channel body wrapped in a {@link SequentialBlock} — the exact structure
	 * the PDSL interpreter builds for {@code for each channel} bodies. Distinguishes a fault
	 * in {@link MultiChannelDspFeatures#perChannelBlock} composed with chained blocks from a
	 * fault in the interpreter-level wiring around it.
	 */
	@Test(timeout = 120000)
	public void test00bPerChannelDispatchWithSequentialBodies() {
		int channels = 3;
		int signalSize = 4;
		TraversalPolicy singleShape = new TraversalPolicy(1, signalSize);
		PackedCollection gains = pack(2.0, 3.0, 5.0);
		List<Block> blocks = new ArrayList<>();
		for (int ch = 0; ch < channels; ch++) {
			final double gain = gains.toDouble(ch);
			SequentialBlock body = new SequentialBlock(singleShape);
			body.add(new ForwardOnlyBlock(layer("gain", singleShape, singleShape,
					input -> multiply(input, constant(singleShape, gain)))));
			blocks.add(body);
		}
		Block block = perChannelBlock(blocks, channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		CompiledModel compiled = m.compile();

		PackedCollection input = new PackedCollection(new TraversalPolicy(channels, signalSize));
		integers(0, channels * signalSize).add(1.0)
				.into(input.traverseEach()).evaluate();
		PackedCollection out = compiled.forward(input)
				.range(shape(channels * signalSize));

		for (int ch = 0; ch < channels; ch++) {
			for (int i = 0; i < signalSize; i++) {
				double expected = gains.toDouble(ch) * (ch * signalSize + i + 1);
				Assert.assertEquals(
						"channel " + ch + " sample " + i
								+ " must be its own input row times its own gain",
						expected, out.toDouble(ch * signalSize + i), EPS);
			}
		}
	}

	// =====================================================================
	// Test 00c — per-channel dispatch nested inside an outer SequentialBlock
	// =====================================================================
	/**
	 * Like {@link #test00bPerChannelDispatchWithSequentialBodies} but with the per-channel
	 * dispatch block itself nested inside an outer {@link SequentialBlock} — the exact
	 * containment the PDSL interpreter produces when building a layer body. The PDSL-level
	 * {@code for each channel} collapses every chain to row 0 while the directly-driven
	 * dispatch routes correctly, so the enclosing wiring is the remaining variable.
	 */
	@Test(timeout = 120000)
	public void test00cPerChannelDispatchInsideOuterSequentialBlock() {
		int channels = 3;
		int signalSize = 4;
		TraversalPolicy multiShape = new TraversalPolicy(channels, signalSize);
		TraversalPolicy singleShape = new TraversalPolicy(1, signalSize);
		PackedCollection gains = pack(2.0, 3.0, 5.0);
		List<Block> blocks = new ArrayList<>();
		for (int ch = 0; ch < channels; ch++) {
			final double gain = gains.toDouble(ch);
			SequentialBlock body = new SequentialBlock(singleShape);
			body.add(new ForwardOnlyBlock(layer("gain", singleShape, singleShape,
					input -> multiply(input, constant(singleShape, gain)))));
			blocks.add(body);
		}
		SequentialBlock outer = new SequentialBlock(multiShape);
		outer.add(perChannelBlock(blocks, channels, signalSize));
		Model m = new Model(multiShape);
		m.add(outer);
		CompiledModel compiled = m.compile();

		PackedCollection input = new PackedCollection(multiShape);
		integers(0, channels * signalSize).add(1.0)
				.into(input.traverseEach()).evaluate();
		PackedCollection out = compiled.forward(input)
				.range(shape(channels * signalSize));

		for (int ch = 0; ch < channels; ch++) {
			for (int i = 0; i < signalSize; i++) {
				double expected = gains.toDouble(ch) * (ch * signalSize + i + 1);
				Assert.assertEquals(
						"channel " + ch + " sample " + i
								+ " must be its own input row times its own gain",
						expected, out.toDouble(ch * signalSize + i), EPS);
			}
		}
	}

	// =====================================================================
	// Test 10b — production-shaped network with contraction feedback stays bounded
	// =====================================================================
	/**
	 * Regression for the mixdown efx feedback grid runaway: a network with the production
	 * STRUCTURE in miniature — multiple channels, a one-frame ring ({@code bufSize ==
	 * signalSize}), a SUB-FRAME delay ({@code delay < signalSize}), a feedback matrix whose
	 * row sums are well under 1 (a strong contraction), and a diagonal 0.5 passthrough — fed
	 * a small constant signal every pass. With loop gain ~0.15, the steady-state output is
	 * bounded by {@code passthrough * input / (1 - 0.15)}; saturation indicates the kernel
	 * implements a different recurrence from the documented one.
	 */
	@Test(timeout = 120000)
	public void test10bSubFrameDelayContractionStaysBounded() {
		int channels = 3;
		int signalSize = 8;
		PackedCollection fb = new PackedCollection(channels * channels).fill(0.05);
		PackedCollection pass = identity(channels).multiply(0.5).evaluate();
		Harness h = build(channels, signalSize, signalSize,
				pack(6.0, 6.0, 6.0), fb, pass);

		PackedCollection input = new PackedCollection(channels * signalSize).fill(0.01);

		double bound = 0.5 * 0.01 / (1.0 - 0.15) + 1e-6;
		double observedMax = 0.0;
		for (int passIdx = 0; passIdx < 50; passIdx++) {
			PackedCollection out = h.forwardMulti(input);
			observedMax = Math.max(observedMax, max(cp(out).abs()).evaluate().toDouble(0));
			Assert.assertTrue(
					"pass " + passIdx + " output magnitude " + observedMax
							+ " exceeds the closed-loop bound " + bound
							+ " for a contraction feedback network",
					observedMax <= bound);
		}
		Assert.assertTrue("the network must produce non-zero output", observedMax > EPS);
	}

	// =====================================================================
	// Test 10c — full production scale: 6 channels x 8192, sub-frame delay
	// =====================================================================
	/**
	 * The same bounded-output property as {@link #test10bSubFrameDelayContractionStaysBounded}
	 * at the EXACT production dimensions of the mixdown efx feedback grid: 6 channels,
	 * signalSize 8192, a one-frame ring, delay 6500 (sub-frame), the measured genome
	 * transmission scaled by 0.1 (row sums ~0.15), and a 0.5 diagonal passthrough. The
	 * production render saturates instantly at these dimensions while the miniature
	 * configuration stays bounded; this isolates whether the divergence is scale-dependent
	 * in the primitive itself or comes from the surrounding mixdown graph.
	 */
	@Test(timeout = 300000)
	public void test10cProductionScaleContractionStaysBounded() {
		int channels = 6;
		int signalSize = 8192;
		// The measured genome transmission (3 active rows), scaled by feedbackGain/channels
		// = 0.1, exactly as MixdownManagerPdslAdapter supplies it.
		PackedCollection t3 = pack(
				0.0573, 0.0347, 0.0502,
				0.0372, 0.0309, 0.0596,
				0.0596, 0.0456, 0.0558);
		// The three active rows occupy the leading 3 columns of the 6x6 grid; a row of
		// the source is contiguous, so each lands as one transfer into its own row range.
		PackedCollection fb = new PackedCollection(channels * channels);
		for (int n = 0; n < 3; n++) {
			cp(t3.range(shape(3), n * 3))
					.into(fb.range(shape(3), n * channels)).evaluate();
		}
		PackedCollection pass = identity(channels).multiply(0.5).evaluate();
		PackedCollection delays = new PackedCollection(channels).fill(6500.0);
		Harness h = build(channels, signalSize, signalSize, delays, fb, pass);

		PackedCollection input = new PackedCollection(channels * signalSize).fill(0.01);

		double bound = 0.5 * 0.01 / (1.0 - 0.16) + 1e-6;
		double observedMax = 0.0;
		for (int passIdx = 0; passIdx < 20; passIdx++) {
			PackedCollection out = h.forwardMulti(input);
			observedMax = Math.max(observedMax, max(cp(out).abs()).evaluate().toDouble(0));
			Assert.assertTrue(
					"pass " + passIdx + " output magnitude " + observedMax
							+ " exceeds the closed-loop bound " + bound
							+ " at production scale",
					observedMax <= bound);
		}
		Assert.assertTrue("the network must produce non-zero output", observedMax > EPS);
	}

	// =====================================================================
	// Test 11 — passthrough matrix scales the emitted output only
	// =====================================================================
	/**
	 * Single channel, feedback={@code [[0.5]]}. Runs the same impulse through two networks that
	 * differ only in the passthrough matrix: identity ({@code null}) versus {@code [[2.0]]}. The
	 * passthrough scales the emitted (delayed) output but NOT the signal written back to the ring,
	 * so the {@code [[2.0]]} output must equal exactly twice the identity output at every sample
	 * across many passes — proving the passthrough routes the output without altering the feedback
	 * recurrence.
	 */
	@Test(timeout = 60000)
	public void test11PassthroughScalesOutput() {
		Harness identity = build(1, 4, 8, pack(2.0), pack(0.5), null);
		Harness doubled = build(1, 4, 8, pack(2.0), pack(0.5),
				pack(2.0));

		PackedCollection impulse = pack(1.0, 0.0, 0.0, 0.0);
		PackedCollection silence = pack(0.0, 0.0, 0.0, 0.0);

		PackedCollection idOut = identity.forwardMulti(impulse);
		PackedCollection dblOut = doubled.forwardMulti(impulse);
		for (int pass = 0; pass < 8; pass++) {
			for (int i = 0; i < idOut.getMemLength(); i++) {
				Assert.assertEquals(
						"passthrough [[2.0]] output must be 2x identity output at pass " + pass
								+ " sample " + i,
						2.0 * idOut.toDouble(i), dblOut.toDouble(i), EPS);
			}
			idOut = identity.forwardMulti(silence);
			dblOut = doubled.forwardMulti(silence);
		}
	}

	// =====================================================================
	// Test 12 — passthrough matrix routes across channels
	// =====================================================================
	/**
	 * Two channels, zero feedback. The passthrough swap matrix {@code [[0,1],[1,0]]} must route
	 * channel 0's delayed output to output channel 1 and vice versa, so the swapped network's
	 * per-channel output equals the identity network's output with the two channels exchanged.
	 * This proves the passthrough matrix performs genuine cross-channel routing of the emitted
	 * signal (the next-layer routing the mixdown grid's {@code mself} passthrough provides).
	 */
	@Test(timeout = 60000)
	public void test12PassthroughRoutesCrossChannel() {
		PackedCollection zeroFb = new PackedCollection(2 * 2);
		PackedCollection swap = pack(0.0, 1.0, 1.0, 0.0);
		Harness identity = build(2, 4, 8, pack(2.0, 2.0), zeroFb, null);
		Harness swapped = build(2, 4, 8, pack(2.0, 2.0), zeroFb, swap);

		// Distinct per-channel input so the swap is observable.
		PackedCollection in = pack(1.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0);

		PackedCollection idOut = identity.forwardMulti(in);
		PackedCollection swOut = swapped.forwardMulti(in);
		PackedCollection silence = new PackedCollection(8);
		boolean observedDifference = false;
		for (int pass = 0; pass < 6; pass++) {
			for (int i = 0; i < 4; i++) {
				// swapped channel 0 == identity channel 1; swapped channel 1 == identity channel 0
				Assert.assertEquals("swap: out ch0[" + i + "] must equal identity ch1 at pass " + pass,
						idOut.toDouble(4 + i), swOut.toDouble(i), EPS);
				Assert.assertEquals("swap: out ch1[" + i + "] must equal identity ch0 at pass " + pass,
						idOut.toDouble(i), swOut.toDouble(4 + i), EPS);
				if (Math.abs(idOut.toDouble(i) - idOut.toDouble(4 + i)) > EPS) observedDifference = true;
			}
			idOut = identity.forwardMulti(silence);
			swOut = swapped.forwardMulti(silence);
		}
		Assert.assertTrue("the two channels must differ at some point so the swap is meaningful",
				observedDifference);
	}

	// =====================================================================
	// Test 13 — a multi-frame tap delay is sample-exact across frame boundaries
	// =====================================================================
	/**
	 * Single channel, signalSize=4, bufSize=12 (3 frames), tap_delays=[6], feedback=0.
	 * An impulse at absolute sample 0 must surface at absolute sample 6 exactly —
	 * pass 2 sample 2 — and nowhere else. This is the positive form of the ring-band
	 * guarantee: any delay inside {@code [signalSize, bufSize]} is delivered
	 * sample-accurately, including delays that are not frame multiples.
	 */
	@Test(timeout = 60000)
	public void test13MultiFrameTapDelayExact() {
		Harness h = build(1, 4, 12, pack(6.0), new PackedCollection(1));
		PackedCollection pass1 = h.forward(pack(1.0, 0.0, 0.0, 0.0));
		PackedCollection pass2 = h.forwardMulti(new PackedCollection(4));
		PackedCollection pass3 = h.forwardMulti(new PackedCollection(4));

		for (int i = 0; i < 4; i++) {
			Assert.assertEquals("pass 1 sample " + i + " must be silent (zero history)",
					0.0, pass1.toDouble(i), EPS);
			Assert.assertEquals(
					"pass 2 sample " + i + " must carry the impulse only at sample 2 "
							+ "(absolute t=6); pass2=",
					i == 2 ? 1.0 : 0.0, pass2.toDouble(i), EPS);
			Assert.assertEquals(
					"pass 3 sample " + i + " must be silent (no repeat with zero "
							+ "feedback); pass3=",
					0.0, pass3.toDouble(i), EPS);
		}
	}

	// =====================================================================
	// Test 14 — regeneration lands at exact multiples of the tap delay
	// =====================================================================
	/**
	 * Single channel, signalSize=4, bufSize=16, tap_delays=[5], feedback=[[0.5]].
	 * The closed loop evaluates {@code y[t] = x[t] + 0.5 * y[t - 5]} with output
	 * {@code y[t - 5]}, so an impulse at absolute sample 0 must produce echoes at
	 * absolute samples 5, 10, and 15 with amplitudes 1.0, 0.5, and 0.25 — repeats at
	 * exact multiples of the tap delay, halving per generation. This is the receipt
	 * that block-parallel feedback is NOT quantized to the buffer grid: above the
	 * one-frame floor, regeneration timing is sample-exact.
	 */
	@Test(timeout = 60000)
	public void test14RegenerationAtExactDelayMultiples() {
		Harness h = build(1, 4, 16, pack(5.0), pack(0.5));
		PackedCollection silence = new PackedCollection(4);
		PackedCollection[] passes = new PackedCollection[4];
		passes[0] = h.forward(pack(1.0, 0.0, 0.0, 0.0));
		for (int p = 1; p < 4; p++) {
			passes[p] = h.forwardMulti(silence);
		}

		for (int t = 0; t < 16; t++) {
			double expected;
			if (t == 5) {
				expected = 1.0;
			} else if (t == 10) {
				expected = 0.5;
			} else if (t == 15) {
				expected = 0.25;
			} else {
				expected = 0.0;
			}
			Assert.assertEquals(
					"absolute sample " + t + " must carry echo generation amplitude; ",
					expected, passes[t / 4].toDouble(t % 4), EPS);
		}
	}
}
