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

package org.almostrealism.studio.persistence.test.support;

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a library store the way the release before per-model feature keying
 * wrote it: each item analysed with frequency data and features in the legacy
 * unkeyed slot (no model identifier), and put with its mean-pooled embedding, so
 * the saved HNSW index holds the old model's vectors.
 */
public final class PreviousReleaseStore {

	/** Not instantiable. */
	private PreviousReleaseStore() { }

	/**
	 * Analyses each file with an old-model {@link LogSpectrumFeatureProvider} and
	 * writes the result to a store at {@code storeDir} in the previous release's
	 * form, then closes the store.
	 *
	 * @param storeDir   the store directory
	 * @param wavFiles   the files to analyse
	 * @param sampleRate the sample rate of the files, in Hz
	 * @param frames     feature frames per item
	 * @param bins       feature width of the old model
	 * @param duration   duration of each file, in seconds
	 * @return the number of vectors the written store indexed
	 */
	public static int write(Path storeDir, List<File> wavFiles, int sampleRate,
							int frames, int bins, double duration) {
		WaveDetailsFactory factory = new WaveDetailsFactory(sampleRate);
		LogSpectrumFeatureProvider oldModel =
				new LogSpectrumFeatureProvider(frames, bins, sampleRate, duration);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		try {
			for (File wav : wavFiles) {
				WaveDetails details = factory.forFile(wav.getAbsolutePath());
				PackedCollection features = oldModel.computeFeatures(details.getWaveData());
				details.setFeatureSampleRate(oldModel.getFeatureSampleRate());
				details.setFeatureChannelCount(1);
				details.setFeatureBinCount(features.getShape().length(1));
				details.setFeatureFrameCount(features.getShape().length(0));
				details.setFeatureData(features);

				store.put(details.getIdentifier(), details, details.meanPooledEmbedding());
			}

			return store.indexedEmbeddingCount();
		} finally {
			store.close();
		}
	}
}
