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

package org.almostrealism.audio.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.SampleMixer;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.SharedMemoryAudioLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.graph.temporal.WaveCell;
import org.junit.Assert;
import org.junit.Test;

public class MixerTests implements CellFeatures {
	@Test
	public void sampleMixer() {
		TimeCell clock = new TimeCell();
		SampleMixer mixer = new SampleMixer(1);
		mixer.init(c -> {
			try {
//				return WaveData.load(new File("Library/RAW_IU_ARCHE_B.wav")).toCell(clock);
				return (WaveCell) w(0, "Library/RAW_IU_ARCHE_B.wav").get(0);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		PackedCollection control = new PackedCollection(SharedMemoryAudioLine.controlSize);
		PackedCollection input = new PackedCollection(BufferDefaults.defaultBufferSize);
		PackedCollection output = new PackedCollection(BufferDefaults.defaultBufferSize);
		SharedMemoryAudioLine line = new SharedMemoryAudioLine(control, input, output);

		BufferedOutputScheduler scheduler = mixer.toCellList().addRequirement(clock).buffer(line);
		scheduler.start();

		try {
			Thread.sleep(4000);
			Assert.assertTrue(output.doubleStream().map(Math::abs).sum() > 0.0);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
