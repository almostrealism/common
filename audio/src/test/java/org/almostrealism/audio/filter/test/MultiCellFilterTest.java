package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

public class MultiCellFilterTest implements CellFeatures, TestFeatures {
	@Test
	public void identity() {
		w(0, "src/test/resources/Snare Perc DD.wav")
				.m(f(2, i -> new IdentityFactor<>()),
						o(2, i -> new File("results/multi-identity-cell-test-" + i + ".wav")),
						i -> g(0.3, 0.5))
				.sec(5).get().run();
	}

	@Test
	public void identityDelay() {
		w(0, "src/test/resources/Snare Perc DD.wav")
				.m(f(2, i -> new IdentityFactor<>()),
						d(2, i -> c(2 * i + 1)),
						i -> g(0.3, 0.5))
				.om(i -> new File("results/multi-identity-delay-cell-test-" + i + ".wav"))
				.sec(8).get().run();
	}

	@Test
	public void identityDelayFeedback() {
		CellList c = w(0, "Library/Snare Perc DD.wav", "Library/Snare Perc DD.wav")
				.d(i -> c(2))
				.m(fi(), c(g(0.0, 0.4), g(0.4, 0.0)))
				.om(i -> new File("results/identity-delay-feedback-test-" + i + ".wav"));
		Supplier<Runnable> op = c.sec(8);
		op.get().run();
	}

	@Test
	public void scale() {
		w(0, "src/test/resources/Snare Perc DD.wav")
				.m(f(2, i -> new ScaleFactor(0.3 * (i + 1))),
						o(2, i -> new File("results/multi-scale-cell-test-" + i + ".wav")),
						i -> g(0.3, 0.5))
				.sec(5).get().run();
	}

	@Test
	public void scaleDelay() throws IOException {
		w(0, "src/test/resources/Snare Perc DD.wav")
				.m(f(2, i -> new ScaleFactor(0.3 * (i + 1))),
						d(2, i -> c(2 * i + 1)),
						i -> g(0.3, 0.5))
				.om(i -> new File("results/multi-scale-delay-cell-test-" + i + ".wav"))
				.sec(8).get().run();
	}

	@Test
	public void scaleDelayFeedback() {
		w(0, "src/test/resources/Snare Perc DD.wav", "src/test/resources/Snare Perc DD.wav")
				.d(i -> c(2))
				.m(f(2, i -> new ScaleFactor(0.45 * (i + 1)))::get, c(g(0.0, 0.5), g(0.5, 0.0)))
				.om(i -> new File("results/scale-delay-feedback-test-" + i + ".wav"))
				.sec(8).get().run();
	}

	@Test
	public void filter() {
		w(0, "src/test/resources/Snare Perc DD.wav")
						.m(f(2, i -> hp(2000, 0.1)),
								o(2, i -> new File("results/multi-filter-cell-test-" + i + ".wav")),
								i -> g(0.3, 0.5))
						.sec(5).get().run();
	}

	@Test
	public void filterDelay() {
		w(0, "src/test/resources/Snare Perc DD.wav")
				.m(f(2, i -> hp(2000, 0.1)),
						d(2, i -> c(2 * i + 1)),
						i -> g(0.3, 0.5))
				.om(i -> new File("result/multi-filter-delay-cell-test-" + i + ".wav"))
				.sec(8).get().run();
	}

	@Test
	public void filterDelayFeedback() {
		w(0, "src/test/resources/Snare Perc DD.wav", "src/test/resources/Snare Perc DD.wav")
				.d(i -> c(2))
				.m(fc(i -> hp(2000, 0.1)),
						c(g(0.0, 0.3), g(0.3, 0.0)))
				.om(i -> new File("results/filter-delay-feedback-test-" + i + ".wav"))
				.sec(8).get().run();
	}
}
