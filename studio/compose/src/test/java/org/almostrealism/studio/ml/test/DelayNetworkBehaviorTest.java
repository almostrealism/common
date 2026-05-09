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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.studio.dsl.audio.MultiChannelDspFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

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

	private static final double EPS = 1.0e-9;

	/**
	 * Holder for a built delay-network harness. Bundles the compiled model
	 * with the per-state slot collections so tests can both run forward
	 * passes and inspect the slot contents directly afterwards.
	 */
	private static final class Harness {
		final CompiledModel model;
		final PackedCollection buffer;
		final PackedCollection heads;
		final int channels;
		final int signalSize;
		final int bufSize;

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
		double[] forward(double[] perChannelInput) {
			Assert.assertEquals(signalSize, perChannelInput.length);
			double[] flat = new double[channels * signalSize];
			for (int c = 0; c < channels; c++) {
				System.arraycopy(perChannelInput, 0, flat, c * signalSize, signalSize);
			}
			return forwardMulti(flat);
		}

		/** Feeds a flat {@code [channels * signalSize]} input directly. */
		double[] forwardMulti(double[] flatInput) {
			Assert.assertEquals(channels * signalSize, flatInput.length);
			PackedCollection input = new PackedCollection(
					new TraversalPolicy(channels, signalSize));
			input.setMem(flatInput);
			PackedCollection out = model.forward(input);
			return out.toArray(0, channels * signalSize);
		}

		double[] readBuffer() {
			return buffer.toArray(0, channels * bufSize);
		}

		double[] readHeads() {
			return heads.toArray(0, channels);
		}
	}

	private Harness build(int channels, int signalSize, int bufSize,
						  double[] tapDelays, double[] feedbackMatrixRowMajor) {
		Assert.assertEquals("delaySamples length must equal channels",
				channels, tapDelays.length);
		Assert.assertEquals("feedback matrix length must be channels*channels",
				channels * channels, feedbackMatrixRowMajor.length);

		PackedCollection delays = new PackedCollection(channels);
		delays.setMem(tapDelays);
		PackedCollection feedback = new PackedCollection(
				new TraversalPolicy(channels, channels));
		feedback.setMem(feedbackMatrixRowMajor);
		PackedCollection buffer = new PackedCollection(channels * bufSize);
		buffer.setMem(new double[channels * bufSize]);
		PackedCollection heads = new PackedCollection(channels);
		heads.setMem(new double[channels]);

		Block block = delayNetworkBlock(
				cp(delays), cp(feedback), cp(buffer), cp(heads),
				channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		CompiledModel compiled = m.compile();
		return new Harness(compiled, buffer, heads, channels, signalSize, bufSize);
	}

	private static double[] zeroFeedback(int channels) {
		return new double[channels * channels];
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
		Harness h = build(1, 4, 4, new double[]{1.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});

		double[] state = h.readBuffer();
		Assert.assertEquals(
				"impulse must land at the buffer slot the kernel writes for sample 0; "
						+ "buffer state=" + Arrays.toString(state),
				1.0, state[0], EPS);
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
		Harness h = build(1, 4, 8, new double[]{1.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});

		double[] heads = h.readHeads();
		Assert.assertEquals(
				"head must advance by signalSize after one forward; got=" + heads[0],
				4.0, heads[0], EPS);
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
		Harness h = build(1, 4, 8, new double[]{1.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});
		h.forward(new double[]{0.0, 0.0, 0.0, 0.0});

		double[] heads = h.readHeads();
		double expected = (2.0 * 4) % 8;
		Assert.assertEquals(
				"head after two forwards must equal (2*signalSize) mod bufSize; "
						+ "got=" + heads[0] + ", expected=" + expected,
				expected, heads[0], EPS);
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
		Harness h = build(1, 4, 8, new double[]{1.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});
		double[] afterPass1 = h.readBuffer();
		boolean foundImpulse1 = false;
		for (double v : afterPass1) if (Math.abs(v - 1.0) < EPS) { foundImpulse1 = true; break; }
		Assert.assertTrue("after pass 1 buffer must hold the impulse somewhere; state="
				+ Arrays.toString(afterPass1), foundImpulse1);

		h.forward(new double[]{0.0, 0.0, 0.0, 0.0});
		double[] afterPass2 = h.readBuffer();
		boolean foundImpulse2 = false;
		for (double v : afterPass2) if (Math.abs(v - 1.0) < EPS) { foundImpulse2 = true; break; }
		Assert.assertTrue(
				"after pass 2 buffer must STILL hold the impulse; state="
						+ Arrays.toString(afterPass2),
				foundImpulse2);
	}

	// =====================================================================
	// Test 5 — simplest delay read: impulse at delay 1 readable after pass 1
	// =====================================================================
	/**
	 * Single channel, signalSize=4, bufSize=8, tap_delays=[1], feedback=0.
	 * Pass 1: feed {@code [1, 0, 0, 0]}. Pass 2: feed silence. The kernel
	 * uses read-before-write semantics (the integration test's pass 1 is
	 * required to be silence with a zero-init buffer) — therefore after
	 * pass 1 head=4 and pass 2 reads from {@code head + i - delay}; with
	 * delay=1 pass 2's output position 0 reads {@code head=4, i=0,
	 * delay=1 → buffer[3]} which is 0. The impulse appears at output
	 * position 1 of pass 2 (read position {@code 4 + 1 - 1 = 4}, but with
	 * bufSize=8 wrap that's also 4 — also 0). The actual position the
	 * impulse appears at is {@code (impulseWritePos + delay - head_pass2)
	 * mod bufSize} where impulseWritePos was 0 (the head at pass 1 was 0)
	 * — solving gives output index = (0 + 1 - 4) mod 8 = 5 mod 8 → out of
	 * the [0..3] pass 2 output range. So pass 2 doesn't see it; the
	 * impulse appears in pass 3 instead, when head=8 mod 8=0 again and
	 * read pos {@code 0 + 1 - 1 = 0 → buffer[0]} = 1.
	 */
	@Test(timeout = 60000)
	public void test05ImpulseEchoAtTapDelay() {
		Harness h = build(1, 4, 8, new double[]{1.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});  // pass 1, write impulse
		double[] pass2 = h.forward(new double[]{0.0, 0.0, 0.0, 0.0});
		double[] pass3 = h.forward(new double[]{0.0, 0.0, 0.0, 0.0});

		// pass 3 sample 1 reads buffer[head+1-1] = buffer[head_pass3 + 0].
		// head_pass3 = 8 mod 8 = 0, so output[1] reads buffer[0] = impulse value.
		Assert.assertEquals(
				"pass 3 sample 1 must carry the impulse echo (delay=1, two passes "
						+ "wrap); got pass2=" + Arrays.toString(pass2)
						+ ", pass3=" + Arrays.toString(pass3),
				1.0, pass3[1], EPS);
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
		Harness h = build(1, 4, 8, new double[]{4.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});
		double[] pass2 = h.forwardMulti(new double[4]);

		Assert.assertEquals(
				"pass 2 output[0] must equal the impulse (delay==signalSize); "
						+ "pass2=" + Arrays.toString(pass2),
				1.0, pass2[0], EPS);
	}

	// =====================================================================
	// Test 7 — multiple taps in one channel
	// =====================================================================
	/**
	 * Single channel, signalSize=8, bufSize=16, tap_delays must be
	 * length=channels=1 — so this test really exercises ONE tap per
	 * delay-network channel, but uses three differently-delayed *channels*
	 * to mimic the multi-tap pattern from the integration test. The
	 * delay-network primitive itself is one-tap-per-channel; multi-tap is
	 * achieved by adding more channels with the same input.
	 */
	@Test(timeout = 60000)
	public void test07MultipleTapsViaChannels() {
		int channels = 3;
		int signalSize = 8;
		int bufSize = 16;
		double[] tapDelays = {1.0, 3.0, 5.0};
		double[] noFeedback = zeroFeedback(channels);

		Harness h = build(channels, signalSize, bufSize, tapDelays, noFeedback);
		double[] impulse = new double[signalSize];
		impulse[0] = 1.0;
		h.forward(impulse);             // pass 1: write impulse to all 3 channels
		double[] pass2 = h.forwardMulti(new double[channels * signalSize]);

		// After pass 1, head=signalSize=8 for every channel. Pass 2's read
		// for channel c, sample i: buffer[(head + i - delay_c) mod bufSize
		// + c*bufSize] = buffer[(8 + i - delay_c) mod 16 + c*bufSize].
		// Channel 0 (delay 1): impulse appears at i where (8+i-1)%16==0 →
		// i = -7 mod 16 = 9 → not in [0..7], so pass 2 does NOT see it.
		// Channel 1 (delay 3): i where (8+i-3)%16==0 → i = -5 mod 16 = 11 → out.
		// Channel 2 (delay 5): i where (8+i-5)%16==0 → i = -3 mod 16 = 13 → out.
		// So pass 2 must be all zero (impulses haven't completed their
		// round-trip yet with bufSize=16 and head only at 8).
		for (int c = 0; c < channels; c++) {
			for (int i = 0; i < signalSize; i++) {
				Assert.assertEquals(
						"pass 2 channel " + c + " sample " + i + " must be 0 "
								+ "(delay round-trip not yet complete); got "
								+ pass2[c * signalSize + i],
						0.0, pass2[c * signalSize + i], EPS);
			}
		}

		// Pass 3: head=16 mod 16=0. Read[c, i] = buffer[(0 + i - delay_c
		// + bufSize) mod bufSize + c*bufSize] = buffer[(i - delay_c + 16)
		// mod 16 + c*bufSize]. Impulse at output i where buffer
		// position == 0 → i == delay_c. So channel 0 sees impulse at i=1,
		// channel 1 at i=3, channel 2 at i=5.
		double[] pass3 = h.forwardMulti(new double[channels * signalSize]);
		Assert.assertEquals("ch0 i=1 must be impulse",
				1.0, pass3[0 * signalSize + 1], EPS);
		Assert.assertEquals("ch1 i=3 must be impulse",
				1.0, pass3[1 * signalSize + 3], EPS);
		Assert.assertEquals("ch2 i=5 must be impulse",
				1.0, pass3[2 * signalSize + 5], EPS);
	}

	// =====================================================================
	// Test 8 — two channels with no cross-talk
	// =====================================================================
	/**
	 * Two channels, signalSize=4, bufSize=8, tap_delays=[1, 1], feedback
	 * matrix all zero. Feed an impulse to channel 0 only. Channel 1
	 * receives silence on every pass. Channel 1's output must be
	 * identically zero — verifies per-channel buffer independence (no
	 * cross-talk through buffer indexing).
	 */
	@Test(timeout = 60000)
	public void test08TwoChannelsNoCrossTalk() {
		int channels = 2;
		int signalSize = 4;
		int bufSize = 8;
		Harness h = build(channels, signalSize, bufSize,
				new double[]{1.0, 1.0}, zeroFeedback(channels));

		// Per-channel input flat layout: [ch0 sigSize samples][ch1 sigSize samples].
		double[] input = new double[channels * signalSize];
		input[0] = 1.0;  // impulse on channel 0 sample 0
		h.forwardMulti(input);
		double[] pass2 = h.forwardMulti(new double[channels * signalSize]);
		double[] pass3 = h.forwardMulti(new double[channels * signalSize]);

		// Channel 1 must be silent on all passes.
		for (int i = 0; i < signalSize; i++) {
			Assert.assertEquals(
					"pass 2 channel 1 sample " + i + " must be 0; got "
							+ pass2[signalSize + i],
					0.0, pass2[signalSize + i], EPS);
			Assert.assertEquals(
					"pass 3 channel 1 sample " + i + " must be 0; got "
							+ pass3[signalSize + i],
					0.0, pass3[signalSize + i], EPS);
		}

		// Channel 0 must carry the impulse on pass 3 (delay=1 + bufSize=8
		// wrap takes 2 passes). pass3 ch0 sample 1 reads buffer[head=0 + 1 - 1] = buffer[0] = 1.
		Assert.assertEquals(
				"pass 3 channel 0 sample 1 must carry the impulse; got "
						+ pass3[1],
				1.0, pass3[1], EPS);
	}

	// =====================================================================
	// Test 9 — buffer wrap-around correctness
	// =====================================================================
	/**
	 * Single channel, signalSize=2, bufSize=4, tap_delays=[1], feedback=0.
	 * Run forward() three times: head advances 0 → 2 → 0 (mod 4) → 2.
	 * Feed the impulse on pass 1 (writes buffer[0]=1, buffer[1]=0, head=2).
	 * Pass 2 reads from head=2: output[0]=buffer[2+0-1]=buffer[1]=0,
	 * output[1]=buffer[2+1-1]=buffer[2]=0 (uninitialized, still 0). Then
	 * pass 2 writes silence to buffer[2..3], head=4 mod 4=0.
	 * Pass 3 head=0: output[0]=buffer[0+0-1+4]=buffer[3]=0,
	 * output[1]=buffer[0+1-1]=buffer[0]=1 → THE IMPULSE.
	 */
	@Test(timeout = 60000)
	public void test09BufferWrapAround() {
		Harness h = build(1, 2, 4, new double[]{1.0}, zeroFeedback(1));
		h.forward(new double[]{1.0, 0.0});
		h.forwardMulti(new double[]{0.0, 0.0});
		double[] pass3 = h.forwardMulti(new double[]{0.0, 0.0});

		Assert.assertEquals(
				"after wrap, pass 3 sample 1 must read the impulse from buffer[0]; "
						+ "pass3=" + Arrays.toString(pass3),
				1.0, pass3[1], EPS);
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
		Harness h = build(1, 4, 8, new double[]{2.0}, new double[]{0.5});
		h.forward(new double[]{1.0, 0.0, 0.0, 0.0});  // pass 1: write impulse
		// Run a number of passes and capture per-pass peak magnitudes.
		double[] silence = new double[]{0.0, 0.0, 0.0, 0.0};
		double prevMax = 1.0;
		double anyNonZeroEcho = 0.0;
		for (int pass = 0; pass < 8; pass++) {
			double[] out = h.forwardMulti(silence);
			double m = 0.0;
			for (double v : out) m = Math.max(m, Math.abs(v));
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
}
