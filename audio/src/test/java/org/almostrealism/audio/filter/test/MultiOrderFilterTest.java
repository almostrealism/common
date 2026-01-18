/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.filter.test;

import io.almostrealism.compute.Process;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class MultiOrderFilterTest implements TestFeatures {

	@Test
	public void lowPass() throws IOException {
		lowPass(false);
	}

	@Test
	public void lowPassOptimized() throws IOException {
		lowPass(true);
	}

	public void lowPass(boolean optimized) throws IOException {
		WaveData data = WaveData.load(new File("Library/Snare Gold 1.wav"));

		MultiOrderFilter filter = lowPass(
					cp(data.getChannelData(0)), c(2000),
					data.getSampleRate(), 40);

		PackedCollection result = (optimized ? Process.optimized(filter) : filter).get().evaluate();
		WaveData output = new WaveData(result, data.getSampleRate());
		output.save(new File("results/multi-order-low-pass" + (optimized ? "-opt" : "") + ".wav"));
	}

	@Test
	public void highPass() throws IOException {
		highPass(false);
	}

	@Test
	public void highPassOptimized() throws IOException {
		highPass(true);
	}

	public void highPass(boolean optimized) throws IOException {
		WaveData data = WaveData.load(new File("Library/Snare Gold 1.wav"));

		MultiOrderFilter filter = highPass(
					cp(data.getChannelData(0)), c(3000),
					data.getSampleRate(), 40);

		PackedCollection result = (optimized ? Process.optimized(filter) : filter).get().evaluate();
		WaveData output = new WaveData(result, data.getSampleRate());
		output.save(new File("results/multi-order-high-pass" + (optimized ? "-opt" : "") + ".wav"));
	}
}
