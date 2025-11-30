/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveData;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class WaveDataSlice implements CellFeatures {
	@Test
	public void slice() throws IOException {
		double sliceDuration = bpm(120).l(1);
		WaveData wave = WaveData.load(new File("/Users/michael/Desktop/Cuba.wav"));

		i: for (int i = 0; ; i++) {
			if ((i + 1) * sliceDuration > wave.getDuration()) {
				break;
			}

			WaveData slice = wave.range(0, i * sliceDuration, sliceDuration);
			slice.save(new File("/Users/michael/Documents/AudioLibrary/Cuba_" + i + ".wav"));
		}
	}
}
