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

import org.almostrealism.audio.BufferedAudioPlayer;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class BufferedAudioPlayerTest implements TestFeatures {
	private PackedCollection data;
	private double total = 0.0;
	private int count = 0;

	@Test
	public void play() throws InterruptedException {
		int sampleRate = OutputLine.sampleRate;
		double duration = 180.0;

		BufferedAudioPlayer player = new BufferedAudioPlayer(1, sampleRate, (int) (duration * sampleRate));
		player.load(0, "Library/RAW_IU_ARCHE_B.wav");

		BufferedOutputScheduler scheduler = player.deliver(new OutputLine() {
			@Override
			public void write(PackedCollection sample) {
				data = sample;
				total = sample.doubleStream().map(Math::abs).sum();
				log("total = " + total);
				count++;
			}
		});

		scheduler.start();

		// Audio should not play until play() is called
		player.play();

		while (count < 8) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		Assert.assertTrue(total > 0.0);
	}
}
