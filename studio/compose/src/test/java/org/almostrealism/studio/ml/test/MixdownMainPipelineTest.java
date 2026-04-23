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
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellList;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.dsl.PdslTemporalBlock;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * Validation tests for Deliverable 3: the {@code pipeline} keyword and
 * {@link PdslTemporalBlock}.
 *
 * <p>Tests verify that:
 * <ul>
 *   <li>{@code mixdown_main_pipeline.pdsl} parses without errors.</li>
 *   <li>{@link PdslLoader#buildPipeline} builds successfully and returns a
 *       non-null {@link PdslTemporalBlock}.</li>
 *   <li>The pipeline output (driven via {@link CellList#addRequirement(org.almostrealism.time.Temporal)})
 *       matches the reference {@code mixdown_main} layer output within numerical tolerance.</li>
 *   <li>A WAV file is produced demonstrating audible pipeline output.</li>
 * </ul>
 *
 * <p>Reference: {@code docs/plans/PDSL_AUDIO_DSP.md} Section 7 and Section 9 Deliverable 3.
 *
 * @see PdslTemporalBlock
 * @see PdslLoader#buildPipeline(PdslNode.Program, String, TraversalPolicy, java.util.Map)
 */
public class MixdownMainPipelineTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Audio buffer size used across tests. */
	private static final int SIGNAL_SIZE = 256;

	/** Sample rate used across tests (Hz). */
	private static final int SAMPLE_RATE = 44100;

	/** FIR filter order. */
	private static final int FILTER_ORDER = 40;

	// ---- Test 1: Parse -------------------------------------------------------------------

	/**
	 * Verifies that {@code mixdown_main_pipeline.pdsl} parses without errors
	 * and contains exactly one pipeline definition named {@code mixdown_main}.
	 */
	@Test(timeout = 30000)
	public void testMixdownMainPipelineParsesCorrectly() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_main_pipeline.pdsl");

		Assert.assertNotNull("Program must not be null", program);

		boolean hasPipeline = false;
		for (PdslNode.Definition def : program.getDefinitions()) {
			if (def instanceof PdslNode.PipelineDef
					&& "mixdown_main".equals(def.getName())) {
				hasPipeline = true;
				PdslNode.PipelineDef pipe = (PdslNode.PipelineDef) def;
				Assert.assertEquals("Input name must be 'channel_audio'",
						"channel_audio", pipe.getInputName());
				Assert.assertEquals("Output name must be 'master_output'",
						"master_output", pipe.getOutputName());
			}
		}
		Assert.assertTrue("mixdown_main PipelineDef must be present", hasPipeline);
	}

	// ---- Test 2: Build -------------------------------------------------------------------

	/**
	 * Builds the pipeline via {@link PdslLoader#buildPipeline} and verifies it does not throw.
	 */
	@Test(timeout = 30000)
	public void testMixdownMainPipelineBuilds() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_main_pipeline.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslTemporalBlock pipeline = loader.buildPipeline(program, "mixdown_main", inputShape, MixdownChannelPdslTest.mainArgs());
		Assert.assertNotNull("PdslTemporalBlock must not be null", pipeline);
	}

	// ---- Test 3: CellList tick output matches reference layer ----------------------------

	/**
	 * Ticks the pipeline through a {@link CellList} and verifies that the captured output
	 * matches the reference {@code mixdown_main} layer's {@code forward()} output on the
	 * same input, within single-precision tolerance.
	 *
	 * <p>Both the pipeline and the reference layer run the same highpass → scale → lowpass
	 * chain compiled from the same PDSL primitives. With identical inputs the outputs must
	 * agree closely.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testPipelineOutputMatchesLayerForward() {
		PdslLoader loader = new PdslLoader();
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		// Build and compile the reference layer (mixdown_main from mixdown_channel.pdsl)
		PdslNode.Program layerProgram = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");
		Block refBlock = loader.buildLayer(layerProgram, "mixdown_main", inputShape, MixdownChannelPdslTest.mainArgs());
		Model refModel = new Model(inputShape);
		refModel.add(refBlock);
		CompiledModel refCompiled = refModel.compile();

		// Build the pipeline
		PdslNode.Program pipelineProgram = loader.parseResource("/pdsl/audio/mixdown_main_pipeline.pdsl");
		PdslTemporalBlock pipeline = loader.buildPipeline(pipelineProgram, "mixdown_main", inputShape, MixdownChannelPdslTest.mainArgs());

		// Create a test signal: 440 Hz sine sweep
		PackedCollection inputSignal = createSignal(SIGNAL_SIZE, i -> {
			double t = (double) i / SAMPLE_RATE;
			return Math.sin(2.0 * Math.PI * 440.0 * t);
		});

		// Capture receptor: evaluates the pushed producer to retrieve the PackedCollection
		PackedCollection[] captured = {null};
		Receptor<PackedCollection> captureReceptor = protein -> {
			captured[0] = protein.get().evaluate();
			return new OperationList();
		};

		// Attach input and output
		pipeline.attachInput("channel_audio", inputSignal);
		pipeline.attachOutput("master_output", captureReceptor);

		// Add to a CellList and tick once
		CellList cells = new CellList();
		cells.addRequirement(pipeline);
		Supplier<Runnable> tickOp = cells.tick();
		tickOp.get().run();

		Assert.assertNotNull("Captured pipeline output must not be null", captured[0]);

		// Reference output via layer forward()
		PackedCollection refOutput = refCompiled.forward(inputSignal);

		// Compare outputs: same primitives should produce very close results
		double[] pipelineOut = captured[0].toArray(0, SIGNAL_SIZE);
		double[] referenceOut = refOutput.toArray(0, SIGNAL_SIZE);

		double maxDiff = 0.0;
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			maxDiff = Math.max(maxDiff, Math.abs(pipelineOut[i] - referenceOut[i]));
		}

		Assert.assertTrue(
				"Pipeline output must match reference layer output within 1e-4 tolerance; "
						+ "max diff = " + maxDiff,
				maxDiff < 1e-4);
	}

	// ---- Test 4: WAV output --------------------------------------------------------------

	/**
	 * Drives the {@code mixdown_main} pipeline via a {@link CellList} over one second of
	 * audio and writes a WAV file to {@code results/pdsl-audio-dsp/}.
	 *
	 * <p>The output must be audibly different from the dry input (HP → volume → LP filtering
	 * is visible in the waveform) and confirms Appendix C of the plan: audio output is the
	 * proof.</p>
	 */
	@Test(timeout = 240000)
	@TestDepth(2)
	public void testMixdownMainPipelineWritesWav() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_main_pipeline.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		PdslTemporalBlock pipeline = loader.buildPipeline(program, "mixdown_main", inputShape, MixdownChannelPdslTest.mainArgs());

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;

		float[] drySignal = new float[totalSamples];
		float[] pipelineSignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			final int offset = pass * SIGNAL_SIZE;

			// Multitone: 440 Hz + 2 kHz + 12 kHz (same as other demo tests)
			PackedCollection inputBuffer = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return 0.33 * Math.sin(2.0 * Math.PI * 440.0 * t)
						+ 0.33 * Math.sin(2.0 * Math.PI * 2000.0 * t)
						+ 0.33 * Math.sin(2.0 * Math.PI * 12000.0 * t);
			});

			PackedCollection[] captured = {null};
			Receptor<PackedCollection> captureReceptor = protein -> {
				captured[0] = protein.get().evaluate();
				return new OperationList();
			};

			pipeline.attachInput("channel_audio", inputBuffer);
			pipeline.attachOutput("master_output", captureReceptor);

			CellList cells = new CellList();
			cells.addRequirement(pipeline);
			cells.tick().get().run();

			Assert.assertNotNull("Pipeline output must not be null for pass " + pass, captured[0]);

			double[] inArr = inputBuffer.toArray(0, SIGNAL_SIZE);
			double[] outArr = captured[0].toArray(0, SIGNAL_SIZE);

			for (int i = 0; i < SIGNAL_SIZE; i++) {
				drySignal[offset + i] = (float) inArr[i];
				pipelineSignal[offset + i] = (float) outArr[i];
			}
		}

		PdslAudioDemoTest.writeDemoWav(new File(outputDir, "mixdown_main_pipeline.wav"), pipelineSignal, SAMPLE_RATE);

		Assert.assertTrue("Pipeline WAV must be non-empty",
				new File(outputDir, "mixdown_main_pipeline.wav").length() > 0);

		// LP filter must attenuate the 12 kHz content relative to the dry signal
		double dryEnergy = energy(floatToDouble(drySignal), FILTER_ORDER);
		double pipelineEnergy = energy(floatToDouble(pipelineSignal), FILTER_ORDER);

		Assert.assertTrue(
				"Pipeline LP filter must attenuate multitone signal containing 12 kHz: "
						+ "dryEnergy=" + dryEnergy + " pipelineEnergy=" + pipelineEnergy,
				pipelineEnergy < dryEnergy * 0.9);
	}

}
