/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.audio.test;

import ai.onnxruntime.OrtException;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.AutoEncoder;
import org.almostrealism.ml.audio.OnnxAutoEncoder;
import org.almostrealism.persist.assets.AssetGroup;
import org.almostrealism.persist.assets.AssetGroupInfo;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.File;

/**
 * Tests for ONNX autoencoder integration.
 */
public class OnnxAutoEncoderTests extends TestSuiteBase {

	/**
	 * Test encoding audio with the ONNX autoencoder.
	 *
	 * <p>Excluded from the {@link TestUtils#PIPELINE} profile: it loads an ONNX model
	 * from {@code assets/stable-audio}, which the CI runners do not carry, and fails
	 * with a {@link NullPointerException} from the ONNX runtime when the directory is
	 * absent. The module itself is in the CI test matrix, so any test added here in
	 * future runs without further wiring.</p>
	 */
	@Test(timeout = 120000)
	@TestProperties(excludeProfiles = TestUtils.PIPELINE)
	public void encode() throws OrtException {
		AssetGroup assets = new AssetGroup(AssetGroupInfo
				.forDirectory(new File("assets/stable-audio")));

		AutoEncoder encoder = new OnnxAutoEncoder(assets);
		PackedCollection input = new PackedCollection(shape(2, 10 * 2048));
		PackedCollection out = encoder.encode(cp(input)).evaluate();
		log(out.getShape());

		for (int i = 0; i < 256; i++) {
			double total = 0.0;

			for (int j = 0; j < 64; j++) {
				total += out.valueAt(0, j, i);
			}

			log("Total for frame " + i + ": " + total);
		}
	}
}
