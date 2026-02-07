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

package org.almostrealism.audio.stream;

import org.almostrealism.audio.BufferedAudioPlayer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.line.SilenceDetectionOutputLine;
import org.almostrealism.audio.persistence.AudioLibraryDataWriter;
import org.almostrealism.audio.persistence.WaveDetailsOutputLine;

import java.io.IOException;

public class AudioSharedMemory implements CellFeatures {
	public static void main(String[] args) throws InterruptedException, IOException {
		new AudioSharedMemory().run();
	}

	public void run() throws IOException, InterruptedException {
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter("recording_test", "recordings");
		WaveDetailsOutputLine record = new WaveDetailsOutputLine(writer);
		record.setActive(true);

		SilenceDetectionOutputLine detector = new SilenceDetectionOutputLine(record);
		record.setSilenceDetector(detector::isSilence);

		AudioStreamManager manager = new AudioStreamManager();
		BufferedAudioPlayer player = manager.addPlayer("live", 2, detector);
		player.load(0, "Library/RAW_IU_RAW_KICK_03.wav");
		player.load(1, "Library/RAW_IU_TOP_15.wav");

		manager.start();
		player.play();
		System.out.println("Server started");

		while (true) {
			Thread.sleep(2000);
//			System.out.println("clock frame = " + player.getClock().getFrame() +
//					" (reset @ " + player.getClock().getReset(0) + ")");
		}
	}
}
