package org.almostrealism.audio.sources.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Tests for audio resampling operations.
 */
public class ResampleTest extends TestSuiteBase implements CellFeatures, AudioTestFeatures {

	/**
	 * Tests loading and resampling a wave file to a specified duration.
	 */
	@Test(timeout = 60000)
	public void resample() throws IOException {
		w(0, getTestWavPath())
				.om(i -> new File("results/resample-test.wav"))
				.sec(3).get().run();
	}
}