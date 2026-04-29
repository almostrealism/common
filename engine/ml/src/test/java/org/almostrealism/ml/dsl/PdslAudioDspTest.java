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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for PDSL audio DSP primitives ({@code fir}, {@code scale},
 * {@code lowpass}, {@code highpass}) and the {@code efx_channel.pdsl}
 * definition file.
 *
 * <p>These tests demonstrate that audio DSP signal processing can be defined
 * declaratively in PDSL and produce correct output, serving as a proof-of-concept
 * for replacing CellList-based DSP with readable PDSL definitions.</p>
 *
 * <p>Structure tests verify parsing and block construction without hardware
 * execution. Execution tests (annotated with {@link TestDepth}) actually run
 * the compiled blocks and compare output to reference implementations.</p>
 *
 * @see PdslInterpreter
 * @see org.almostrealism.time.computations.MultiOrderFilter
 */
public class PdslAudioDspTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Audio buffer size used across tests. */
	private static final int SIGNAL_SIZE = 256;

	/** Sample rate used across tests (Hz). */
	private static final int SAMPLE_RATE = 44100;

	/** Filter order matching EfxManager.filterOrder. */
	private static final int FILTER_ORDER = 40;

	/** Low-pass cutoff for tests (Hz). */
	private static final double LP_CUTOFF = 5000.0;

	/** High-pass cutoff for tests (Hz). */
	private static final double HP_CUTOFF = 200.0;

	/** Wet output level used in efx_wet_chain tests. */
	private static final double WET_LEVEL = 0.5;

	// ---- Parsing / structure tests -----------------------------------------------

	/**
	 * Verifies that the {@code efx_channel.pdsl} file parses correctly and
	 * contains all expected layer definitions.
	 */
	@Test(timeout = 30000)
	public void testEfxChannelPdslParsesCorrectly() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Assert.assertNotNull("Program should not be null", program);

		PdslInterpreter interpreter = new PdslInterpreter(program);
		Assert.assertTrue("Should define 'efx_wet_chain' layer",
				interpreter.getLayerNames().contains("efx_wet_chain"));
		Assert.assertTrue("Should define 'efx_lowpass_wet' layer",
				interpreter.getLayerNames().contains("efx_lowpass_wet"));
		Assert.assertTrue("Should define 'efx_highpass_wet' layer",
				interpreter.getLayerNames().contains("efx_highpass_wet"));
		Assert.assertTrue("Should define 'efx_dry_path' layer",
				interpreter.getLayerNames().contains("efx_dry_path"));
	}

	/**
	 * Verifies that an {@code efx_wet_chain} block can be built from
	 * pre-computed FIR coefficients using the {@code fir()} primitive.
	 */
	@Test(timeout = 30000)
	public void testEfxWetChainBlockBuilds() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		double[] coeffs = referenceLowPassCoefficients(LP_CUTOFF, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection filterCoeffs = new PackedCollection(FILTER_ORDER + 1);
		filterCoeffs.setMem(coeffs);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("filter_coeffs", filterCoeffs);
		args.put("wet_level", WET_LEVEL);

		Block block = loader.buildLayer(program, "efx_wet_chain", inputShape, args);

		Assert.assertNotNull("efx_wet_chain block should not be null", block);
		Assert.assertNotNull("Block should have input shape", block.getInputShape());
		Assert.assertEquals("Input shape total size should match signal size",
				SIGNAL_SIZE, block.getInputShape().getTotalSize());
	}

	/**
	 * Verifies that an {@code efx_lowpass_wet} block can be built using the
	 * {@code lowpass()} PDSL primitive. This exercises inline FIR coefficient
	 * computation without pre-computing them in Java.
	 */
	@Test(timeout = 30000)
	public void testEfxLowpassWetBlockBuilds() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("cutoff", LP_CUTOFF);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		args.put("wet_level", WET_LEVEL);

		Block block = loader.buildLayer(program, "efx_lowpass_wet", inputShape, args);

		Assert.assertNotNull("efx_lowpass_wet block should not be null", block);
		Assert.assertNotNull("Block should have input shape", block.getInputShape());
	}

	/**
	 * Verifies that an {@code efx_highpass_wet} block can be built using the
	 * {@code highpass()} PDSL primitive.
	 */
	@Test(timeout = 30000)
	public void testEfxHighpassWetBlockBuilds() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("cutoff", HP_CUTOFF);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		args.put("wet_level", WET_LEVEL);

		Block block = loader.buildLayer(program, "efx_highpass_wet", inputShape, args);

		Assert.assertNotNull("efx_highpass_wet block should not be null", block);
		Assert.assertNotNull("Block should have input shape", block.getInputShape());
	}

	/**
	 * Verifies that inline PDSL source with {@code fir()}, {@code scale()},
	 * {@code lowpass()}, and {@code highpass()} primitives all parse and build
	 * blocks successfully.
	 */
	@Test(timeout = 30000)
	public void testAudioPrimitivesParseAndBuild() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_audio_primitives.pdsl");

		PdslInterpreter interpreter = new PdslInterpreter(program);
		Assert.assertTrue("Should define 'fir_filter' layer",
				interpreter.getLayerNames().contains("fir_filter"));
		Assert.assertTrue("Should define 'scale_layer' layer",
				interpreter.getLayerNames().contains("scale_layer"));
		Assert.assertTrue("Should define 'lp_filter' layer",
				interpreter.getLayerNames().contains("lp_filter"));
		Assert.assertTrue("Should define 'hp_filter' layer",
				interpreter.getLayerNames().contains("hp_filter"));

		TraversalPolicy shape = new TraversalPolicy(1, SIGNAL_SIZE);

		double[] coeffs = referenceLowPassCoefficients(LP_CUTOFF, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection filterCoeffs = new PackedCollection(FILTER_ORDER + 1);
		filterCoeffs.setMem(coeffs);

		// Build fir_filter
		Map<String, Object> firArgs = new HashMap<>();
		firArgs.put("coeffs", filterCoeffs);
		Block firBlock = loader.buildLayer(program, "fir_filter", shape, firArgs);
		Assert.assertNotNull("fir_filter block should not be null", firBlock);

		// Build scale_layer (uses signal_size for shape inference via return annotation)
		Map<String, Object> scaleArgs = new HashMap<>();
		scaleArgs.put("signal_size", SIGNAL_SIZE);
		scaleArgs.put("factor", 0.5);
		Block scaleBlock = loader.buildLayer(program, "scale_layer", shape, scaleArgs);
		Assert.assertNotNull("scale_layer block should not be null", scaleBlock);

		// Build lp_filter
		Map<String, Object> lpArgs = new HashMap<>();
		lpArgs.put("signal_size", SIGNAL_SIZE);
		lpArgs.put("cutoff", LP_CUTOFF);
		lpArgs.put("sr", (double) SAMPLE_RATE);
		lpArgs.put("order", (double) FILTER_ORDER);
		Block lpBlock = loader.buildLayer(program, "lp_filter", shape, lpArgs);
		Assert.assertNotNull("lp_filter block should not be null", lpBlock);

		// Build hp_filter
		Map<String, Object> hpArgs = new HashMap<>();
		hpArgs.put("signal_size", SIGNAL_SIZE);
		hpArgs.put("cutoff", HP_CUTOFF);
		hpArgs.put("sr", (double) SAMPLE_RATE);
		hpArgs.put("order", (double) FILTER_ORDER);
		Block hpBlock = loader.buildLayer(program, "hp_filter", shape, hpArgs);
		Assert.assertNotNull("hp_filter block should not be null", hpBlock);
	}

	// ---- Execution tests ---------------------------------------------------------

	/**
	 * Verifies that the PDSL {@code fir()} primitive produces the same output
	 * as the reference Java FIR convolution when the efx_wet_chain block is
	 * compiled and executed.
	 *
	 * <p>Processes a sine-wave signal through an {@code efx_wet_chain} block
	 * (LP filter + wet scaling) and compares the result to the reference
	 * convolution scaled by the wet level.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testEfxWetChainExecution() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PackedCollection signal = createSignal(SIGNAL_SIZE,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));

		double[] coeffs = referenceLowPassCoefficients(LP_CUTOFF, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection filterCoeffs = new PackedCollection(FILTER_ORDER + 1);
		filterCoeffs.setMem(coeffs);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("filter_coeffs", filterCoeffs);
		args.put("wet_level", WET_LEVEL);

		Block block = loader.buildLayer(program, "efx_wet_chain", inputShape, args);

		// Compile and execute the PDSL-defined block
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection output = compiled.forward(signal);
		Assert.assertNotNull("Output should not be null", output);

		// Compare to reference: lowpass(signal) * wet_level
		double[] expected = referenceConvolve(signal.toArray(0, SIGNAL_SIZE), coeffs);
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			assertEquals(expected[i] * WET_LEVEL, output.toDouble(i));
		}
	}

	/**
	 * Verifies that the PDSL {@code lowpass()} primitive, when applied via
	 * the {@code efx_lowpass_wet} layer, produces output consistent with a
	 * reference low-pass FIR filter.
	 *
	 * <p>A signal dominated by high-frequency content should be significantly
	 * attenuated after passing through the low-pass filter.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testLowpassPrimitiveAttenuation() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		// High-frequency sine (aliased near Nyquist) — should be attenuated by LP filter
		PackedCollection signal = createSignal(SIGNAL_SIZE,
				i -> Math.sin(2.0 * Math.PI * i / 4.0));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("cutoff", LP_CUTOFF);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		args.put("wet_level", 1.0);

		Block block = loader.buildLayer(program, "efx_lowpass_wet", inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection output = compiled.forward(signal);
		Assert.assertNotNull("Output should not be null", output);

		// The LP filter should heavily attenuate a ~11025 Hz signal when cutoff is 5000 Hz.
		// Verify by checking that RMS of output is significantly less than input RMS.
		double inputRms = 0.0;
		double outputRms = 0.0;
		// Skip filter edge samples (first filterOrder/2 and last filterOrder/2)
		int skipEdge = FILTER_ORDER / 2;
		int count = SIGNAL_SIZE - 2 * skipEdge;
		for (int i = skipEdge; i < SIGNAL_SIZE - skipEdge; i++) {
			inputRms += signal.toDouble(i) * signal.toDouble(i);
			outputRms += output.toDouble(i) * output.toDouble(i);
		}
		inputRms = Math.sqrt(inputRms / count);
		outputRms = Math.sqrt(outputRms / count);

		// Output energy should be significantly reduced (at least 80% reduction)
		Assert.assertTrue(
				"LP filter should attenuate high-frequency signal significantly; " +
						"inputRms=" + inputRms + " outputRms=" + outputRms,
				outputRms < inputRms * 0.2);
	}

	// ---- State block tests -------------------------------------------------------

	/**
	 * Verifies that a PDSL source containing a {@code state} block parses correctly
	 * and the interpreter reports the state definition name alongside the layer.
	 */
	@Test(timeout = 30000)
	public void testStateBlockParsesCorrectly() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_state_primitives.pdsl");
		PdslInterpreter interpreter = new PdslInterpreter(program);
		Assert.assertTrue("Should define 'biquad_state' state block",
				interpreter.getStateDefNames().contains("biquad_state"));
		Assert.assertTrue("Should define 'biquad_layer' layer",
				interpreter.getLayerNames().contains("biquad_layer"));
	}

	/**
	 * Verifies that a biquad layer defined with a state block can be built
	 * into a {@link Block} without errors and without hardware execution.
	 */
	@Test(timeout = 30000)
	public void testBiquadStateBlockBuilds() {
		PackedCollection history = new PackedCollection(4);
		history.setMem(new double[]{0.0, 0.0, 0.0, 0.0});

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_state_primitives.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("b0", 1.0);
		args.put("b1", 0.0);
		args.put("b2", 0.0);
		args.put("a1", 0.0);
		args.put("a2", 0.0);
		args.put("history", history);

		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);
		Block block = loader.buildLayer(program, "biquad_layer", inputShape, args);
		Assert.assertNotNull("biquad_layer block should not be null", block);
	}

	/**
	 * Verifies that biquad filter state persists across successive
	 * {@link CompiledModel#forward} calls by using a one-sample-delay filter
	 * ({@code b1=1}, all others zero) and checking that the first output of the
	 * second call reflects the last input sample of the first call.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testBiquadStatePersistence() {
		PackedCollection history = new PackedCollection(4);
		history.setMem(new double[]{0.0, 0.0, 0.0, 0.0});

		// One-sample delay: y[n] = x[n-1]
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_state_primitives.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("b0", 0.0);
		args.put("b1", 1.0);
		args.put("b2", 0.0);
		args.put("a1", 0.0);
		args.put("a2", 0.0);
		args.put("history", history);

		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);
		Block block = loader.buildLayer(program, "biquad_layer", inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// First call: all-ones input, history starts at zero
		PackedCollection signal1 = createSignal(SIGNAL_SIZE, i -> 1.0);
		PackedCollection output1 = compiled.forward(signal1);
		Assert.assertNotNull("First output should not be null", output1);
		// output1[0] must be 0 (no prior history), rest must be 1.0
		Assert.assertEquals("First call output[0] should be 0 (no history yet)",
				0.0, output1.toDouble(0), 1e-6);

		// Second call: all-twos input; output[0] must reflect last sample from first call
		PackedCollection signal2 = createSignal(SIGNAL_SIZE, i -> 2.0);
		PackedCollection output2 = compiled.forward(signal2);
		Assert.assertNotNull("Second output should not be null", output2);
		// history[0] was set to 1.0 by the first call, so output2[0] = x[-1] = 1.0
		Assert.assertEquals("Second call output[0] should be 1.0 (persisted from first call)",
				1.0, output2.toDouble(0), 1e-6);
	}

	/**
	 * Verifies that the efx_delay layer (from {@code efx_channel.pdsl}) produces
	 * delay-line output that is continuous across successive forward passes.
	 *
	 * <p>After a first pass of all-ones, the write-head and buffer are updated;
	 * the first two output samples of the second pass must come from the buffer
	 * written during the first pass.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testDelayLineStatePersistence() {
		int delaySamples = 2;
		PackedCollection buffer = new PackedCollection(SIGNAL_SIZE);
		buffer.setMem(new double[SIGNAL_SIZE]);
		PackedCollection head = new PackedCollection(1);
		head.setMem(new double[]{0.0});

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("delay_samples", delaySamples);
		args.put("buffer", buffer);
		args.put("head", head);

		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);
		Block block = loader.buildLayer(program, "efx_delay", inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// First call: all-ones; first 2 outputs are 0 (empty buffer), rest are 1.0
		PackedCollection signal1 = createSignal(SIGNAL_SIZE, i -> 1.0);
		PackedCollection output1 = compiled.forward(signal1);
		Assert.assertNotNull("First output should not be null", output1);
		Assert.assertEquals("First call output[0] should be 0 (empty buffer)",
				0.0, output1.toDouble(0), 1e-6);
		Assert.assertEquals("First call output[1] should be 0 (empty buffer)",
				0.0, output1.toDouble(1), 1e-6);

		// Second call: all-twos; first 2 outputs must come from first-call buffer (value 1.0)
		PackedCollection signal2 = createSignal(SIGNAL_SIZE, i -> 2.0);
		PackedCollection output2 = compiled.forward(signal2);
		Assert.assertNotNull("Second output should not be null", output2);
		Assert.assertEquals("Second call output[0] should be 1.0 (from first-call buffer)",
				1.0, output2.toDouble(0), 1e-6);
		Assert.assertEquals("Second call output[1] should be 1.0 (from first-call buffer)",
				1.0, output2.toDouble(1), 1e-6);
	}

	/**
	 * Verifies that LFO phase is continuous across successive forward passes.
	 *
	 * <p>The first call produces samples starting at phase zero; the second call
	 * must start exactly where the first left off. The expected phase after the
	 * first call is computed using the same wrapping logic as the interpreter.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testLfoPhaseContiguity() {
		double freqHz = 440.0;
		double sampleRate = 44100.0;
		PackedCollection phase = new PackedCollection(1);
		phase.setMem(new double[]{0.0});

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_state_primitives.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("freq_hz", freqHz);
		args.put("sample_rate", sampleRate);
		args.put("phase", phase);

		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);
		Block block = loader.buildLayer(program, "lfo_layer", inputShape, args);

		// Use any signal (lfo output ignores input)
		PackedCollection signal = createSignal(SIGNAL_SIZE, i -> 0.0);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// First call: LFO starts at phase=0
		PackedCollection output1 = compiled.forward(signal);
		Assert.assertNotNull("First LFO output should not be null", output1);
		Assert.assertEquals("LFO output[0] in first call should be sin(0)",
				Math.sin(0.0), output1.toDouble(0), 1e-6);

		// Compute expected phase after SIGNAL_SIZE samples using the same wrapping logic
		double phaseInc = 2.0 * Math.PI * freqHz / sampleRate;
		double expectedPhase = 0.0;
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			expectedPhase += phaseInc;
			if (expectedPhase >= 2.0 * Math.PI) {
				expectedPhase -= 2.0 * Math.PI;
			}
		}

		// Second call must start at the expected continuation phase
		PackedCollection output2 = compiled.forward(signal);
		Assert.assertNotNull("Second LFO output should not be null", output2);
		Assert.assertEquals("Second LFO call output[0] must continue from first call's end phase",
				Math.sin(expectedPhase), output2.toDouble(0), 1e-6);
	}

	// ---- producer([shape]) parameter tests --------------------------------------
	//
	// These tests exercise the producer-valued `scale` primitive: a parameter
	// declared as `producer([1])` accepts a Number (constant-folded), a
	// 1-element PackedCollection (mutable slot), or a Producer<PackedCollection>
	// (general expression). See docs/plans/PDSL_AUDIO_DSP.md Section 11.

	/**
	 * Verifies that a {@code producer([1])} parameter supplied as a Number literal
	 * constant-folds into the kernel and produces output equal to the literal
	 * times each input element. Proves the producer pathway does not regress the
	 * fixed-parameter case.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testScaleProducerLiteralConstantFolding() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_producer_scale.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("volume", 0.5);  // Number literal — should constant-fold

		Block block = loader.buildLayer(program, "scale_producer_layer", inputShape, args);
		Assert.assertNotNull("scale_producer_layer block must not be null", block);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection signal = createSignal(SIGNAL_SIZE,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));
		PackedCollection output = compiled.forward(signal);
		Assert.assertNotNull("Output must not be null", output);

		for (int i = 0; i < SIGNAL_SIZE; i++) {
			Assert.assertEquals("Output[" + i + "] must equal 0.5 * input[" + i + "]",
					0.5 * signal.toDouble(i), output.toDouble(i), 1e-9);
		}
	}

	/**
	 * Verifies that a {@code producer([1])} parameter supplied as a 1-element
	 * {@link PackedCollection} reads the slot's current value on each forward
	 * pass. Mutating the slot between {@code forward()} calls must be reflected
	 * in the output without rebuilding the layer.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testScaleProducerMutableSlot() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PackedCollection volumeSlot = new PackedCollection(1);
		volumeSlot.setMem(0, 0.25);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_producer_scale.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("volume", volumeSlot);

		Block block = loader.buildLayer(program, "scale_producer_layer", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection signal = createSignal(SIGNAL_SIZE,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));

		PackedCollection output1 = compiled.forward(signal);
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			Assert.assertEquals("Pass 1 output[" + i + "] must equal 0.25 * input[" + i + "]",
					0.25 * signal.toDouble(i), output1.toDouble(i), 1e-9);
		}

		// Mutate the slot between forward calls — the second pass must reflect it.
		volumeSlot.setMem(0, 0.75);

		PackedCollection output2 = compiled.forward(signal);
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			Assert.assertEquals("Pass 2 output[" + i + "] must equal 0.75 * input[" + i + "]",
					0.75 * signal.toDouble(i), output2.toDouble(i), 1e-9);
		}
	}

	/**
	 * Verifies that a {@code producer([1])} parameter supplied as a composed
	 * {@link Producer} expression (clock-style: a slot read multiplied by a
	 * constant gain) is read correctly by the kernel. The slot acts as a
	 * counter advanced between forward passes; the output reflects the current
	 * counter value times the constant gain, demonstrating that the producer
	 * pathway handles arbitrary producer expressions and not just direct slot
	 * reads.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testScaleProducerClockDriven() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PackedCollection counter = new PackedCollection(1);
		counter.setMem(0, 0.1);

		// Composed producer: counter * 0.5. Demonstrates that a derived producer
		// expression — not just a direct cp(slot) — is correctly embedded in the
		// kernel and re-evaluated when the underlying slot changes.
		Producer<PackedCollection> volumeProducer = cp(counter).multiply(c(0.5));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_producer_scale.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("volume", volumeProducer);

		Block block = loader.buildLayer(program, "scale_producer_layer", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection signal = createSignal(SIGNAL_SIZE, i -> 1.0);

		// "Clock" tick 1 — counter = 0.1, expected scale = 0.05
		PackedCollection output1 = compiled.forward(signal);
		double expected1 = 0.1 * 0.5;
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			Assert.assertEquals("Tick 1 output[" + i + "] must equal counter * 0.5",
					expected1, output1.toDouble(i), 1e-9);
		}

		// Snapshot the value before pass 2 overwrites the shared buffer.
		double output1FirstSample = output1.toDouble(0);

		// Advance the "clock" — counter = 0.4, expected scale = 0.2
		counter.setMem(0, 0.4);
		PackedCollection output2 = compiled.forward(signal);
		double expected2 = 0.4 * 0.5;
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			Assert.assertEquals("Tick 2 output[" + i + "] must equal counter * 0.5",
					expected2, output2.toDouble(i), 1e-9);
		}

		Assert.assertNotEquals("Outputs across clock ticks must differ",
				output1FirstSample, output2.toDouble(0), 1e-9);
	}

	/**
	 * Verifies that the {@code producer([1])} parameter form rejects a
	 * {@link PackedCollection} whose shape does not match the declared shape.
	 */
	@Test(timeout = 30000)
	public void testScaleProducerShapeMismatchRejected() {
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/test_producer_scale.pdsl");

		// Wrong shape: 4 elements, but declared as producer([1]).
		PackedCollection wrongShape = new PackedCollection(4);
		wrongShape.setMem(new double[]{0.0, 0.0, 0.0, 0.0});

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("volume", wrongShape);

		try {
			loader.buildLayer(program, "scale_producer_layer", inputShape, args);
			Assert.fail("Building with mismatched producer shape must throw");
		} catch (PdslParseException expected) {
			if (!expected.getMessage().contains("volume")) {
				Assert.fail("Error must reference the parameter name 'volume', but got: "
						+ expected.getMessage());
			}
		}
	}

}
