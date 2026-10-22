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

package org.almostrealism.audio.filter.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Profiles the {@link DelayNetwork} reverb render that {@link ReverbCellTests#reverb1()}
 * performs, over a shorter duration, and saves the operation profile to
 * {@code results/reverbLoop.xml} for inspection with the profile analyzer.
 */
public class ReverbLoopProfileTest extends TestSuiteBase implements CellFeatures, AudioTestFeatures {

	/**
	 * Renders a short reverb pass through the same cell graph as
	 * {@link ReverbCellTests#reverb1()} under an operation profile.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 4 * 60000)
	public void profileReverbLoop() throws IOException {
		double seconds = Double.parseDouble(System.getProperty("AR_REVERB_PROFILE_SECONDS", "0.25"));
		OperationProfileNode profile = new OperationProfileNode("reverbLoop");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			long constructStart = System.nanoTime();
			CellList c = w(0, getTestWavPath())
					.map(fc(i -> new DelayNetwork(OutputLine.sampleRate, false)))
					.o(i -> new File("results/reverb-loop-profile.wav"));
			log("constructMs=" + (System.nanoTime() - constructStart) / 1e6);
			OperationList op = (OperationList) c.sec(seconds);

			long start = System.nanoTime();
			Runnable r = op.get(profile);
			long compiled = System.nanoTime();
			r.run();
			long end = System.nanoTime();

			log("seconds=" + seconds + " frames=" + (int) (seconds * OutputLine.sampleRate)
					+ " compileMs=" + (compiled - start) / 1e6
					+ " runMs=" + (end - compiled) / 1e6);
		} finally {
			profile.save("results/reverbLoop.xml");
		}
	}
}
