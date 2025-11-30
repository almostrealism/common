package org.almostrealism.audio.sources.test;

import org.almostrealism.audio.CellFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class ResampleTest implements CellFeatures {
	@Test
	public void resample() throws IOException {
		w(0, "src/test/resources/161858-SFX-Whoosh-Deep_Phase.wav")
				.om(i -> new File("results/resample-test.wav"))
				.sec(3).get().run();
	}
}
