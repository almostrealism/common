package org.almostrealism.audio.sources.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;

public class MixdownTest extends TestSuiteBase implements CellFeatures, AudioTestFeatures {
	@Test
	public void samples() {
		int count = 32;

		CellList cells =
				silence().and(w(0, c(bpm(128).l(0.5)), c(bpm(128).l(1)), getTestWavPath()))
						.gr(bpm(128).l(count), count * 2, i -> i % 2 == 0 ? 0 : 1)
						.f(i -> i == 0 ? new ScaleFactor(0.5) : new ScaleFactor(0.1))
						.sum().mixdown(bpm(128).l(count));
		cells = cells.o(i -> new File("results/mixdown-test.wav"));
		OperationList op = (OperationList) cells.sec(bpm(128).l(count));
		op.get().run();
	}
}
