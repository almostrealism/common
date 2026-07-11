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

package org.almostrealism.graph.model.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Diagnostic comparison of copy-based versus assignment-based layer input/output recording
 * ({@link DefaultCellularLayer#enableMemoryDataCopy}) for the exact model shape that
 * diverges during training when assignment-based recording is enabled (a single
 * {@code dense(2, 1)} layer, as in gradient-descent regression tests). One forward and
 * one backward pass run under each mode with identical weights and input, and the
 * recorded buffers and updated weights are reported side by side.
 */
public class AssignmentRecordingDiagTest extends TestSuiteBase implements ModelTestFeatures {
	/** Directory where the diagnostic operation profiles are written. */
	private static final Path RESULTS_DIR = Paths.get("results");

	/**
	 * Runs one forward/backward step of a fixed dense(2,1) model under both recording
	 * modes and reports the recorded input, recorded output, forward result, and
	 * post-step weights for each.
	 */
	@Test(timeout = 120000)
	public void compareRecordingModes() throws IOException {
		Files.createDirectories(RESULTS_DIR);

		boolean original = DefaultCellularLayer.enableMemoryDataCopy;

		try {
			runStep(true);
			runStep(false);
		} finally {
			DefaultCellularLayer.enableMemoryDataCopy = original;
		}
	}

	/**
	 * Builds the fixed model, runs one forward and one backward pass under the given
	 * recording mode, and logs every observable buffer.
	 *
	 * @param copyMode value for {@link DefaultCellularLayer#enableMemoryDataCopy}
	 */
	private void runStep(boolean copyMode) throws IOException {
		DefaultCellularLayer.enableMemoryDataCopy = copyMode;
		String mode = copyMode ? "copyRecording" : "assignmentRecording";

		PackedCollection weights = new PackedCollection(shape(1, 2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection biases = new PackedCollection(shape(1));
		biases.setMem(0, 0.1);

		CellularLayer layer = dense(weights, biases).apply(shape(2));

		SequentialBlock block = new SequentialBlock(shape(2));
		block.add(layer);

		Model model = new Model(shape(2), 0.1);
		model.add(block);

		OperationProfileNode profile = new OperationProfileNode(mode);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			CompiledModel compiled = model.compile(true, profile);

			PackedCollection input = new PackedCollection(shape(2));
			input.setMem(0, 2.0, 3.0);

			PackedCollection out = compiled.forward(input);
			log(mode + " forward=" + out.toDouble(0) +
					" expected=" + (0.5 * 2.0 - 0.25 * 3.0 + 0.1));

			DefaultCellularLayer dcl = (DefaultCellularLayer) layer;
			log(mode + " recordedInput=" + dcl.getInput().toDouble(0) +
					"," + dcl.getInput().toDouble(1) + " expected=2.0,3.0");
			log(mode + " recordedOutput=" + dcl.getOutput().toDouble(0));

			PackedCollection gradient = new PackedCollection(shape(1));
			gradient.setMem(0, 1.0);
			compiled.backward(gradient);

			// With learning rate 0.1 and gradient 1.0, the weight update for w_i is
			// -0.1 * input_i and the bias update is -0.1.
			log(mode + " weightsAfter=" + weights.toDouble(0) + "," + weights.toDouble(1) +
					" expected=" + (0.5 - 0.1 * 2.0) + "," + (-0.25 - 0.1 * 3.0));
			log(mode + " biasAfter=" + biases.toDouble(0) + " expected=" + (0.1 - 0.1));

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(mode + ".xml").toString();
		profile.save(profilePath);
		log("Profile saved to: " + profilePath);
	}
}
