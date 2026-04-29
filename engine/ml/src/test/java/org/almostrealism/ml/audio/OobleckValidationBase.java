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

package org.almostrealism.ml.audio;

import org.almostrealism.util.TestSuiteBase;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared base for Oobleck layer validation tests, providing common paths and utilities.
 */
public abstract class OobleckValidationBase extends TestSuiteBase {

	protected static final Path TEST_DATA_DIR = Paths.get("test_data/stable_audio");
	protected static final Path WEIGHTS_DIR = TEST_DATA_DIR.resolve("weights");
	protected static final Path REFERENCE_DIR = TEST_DATA_DIR.resolve("reference");

	/**
	 * Loads a reference binary output file produced by the PyTorch reference implementation.
	 *
	 * @param filename the file name within the reference directory
	 * @return the float values stored in the file
	 * @throws IOException if the file cannot be read
	 */
	protected float[] loadReferenceOutput(String filename) throws IOException {
		Path filepath = REFERENCE_DIR.resolve(filename);
		try (DataInputStream dis = new DataInputStream(new FileInputStream(filepath.toFile()))) {
			byte[] countBytes = new byte[4];
			dis.readFully(countBytes);
			int count = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

			float[] values = new float[count];
			byte[] floatBytes = new byte[4];
			for (int i = 0; i < count; i++) {
				dis.readFully(floatBytes);
				values[i] = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
			}
			return values;
		}
	}

	/**
	 * Computes statistics for an array of values.
	 *
	 * @param values the values to analyse
	 * @return [min, max, mean, stddev]
	 */
	protected double[] computeStats(float[] values) {
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		double sum = 0;

		for (float v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
			sum += v;
		}

		double mean = sum / values.length;

		double sumSqDiff = 0;
		for (float v : values) {
			sumSqDiff += (v - mean) * (v - mean);
		}
		double stddev = Math.sqrt(sumSqDiff / values.length);

		return new double[]{min, max, mean, stddev};
	}
}
