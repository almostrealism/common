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

package org.almostrealism.studio.arrange.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Foundational tests of {@link TimeCell} and {@link AutomationManager} that
 * establish the pieces of the clock-driven producer chain work in isolation.
 * These are the building blocks relied on by
 * {@link org.almostrealism.studio.arrange.AutomationManager}-driven audio
 * automation. They all pass; they should stay passing.
 *
 * <p>The narrowly-scoped reproducer for the observed kernel-caching bug that
 * motivated this test class lives in
 * {@code ProducerEvalCachesKernelTest} &mdash; that one shows the smallest
 * construct (a Producer, a compiled Loop-driven Assignment, and a pre-loop
 * {@code producer.get().evaluate()} call) that defeats liveness. The tests
 * here confirm that none of the individual pieces (frame read/write, Java-
 * driven eval at varying clock positions, compiled loop advancement of the
 * clock) are themselves broken.</p>
 *
 * <p><b>Historical context:</b> earlier revisions of this class included a
 * dozen bisection tests (bisect1..bisect11) that progressively added
 * arithmetic complexity to a frame-driven expression, plus a
 * {@code aggregatedValueLiveInsideCompiledLoop} high-level reproducer. All
 * of those are superseded by {@code ProducerEvalCachesKernelTest}; they've
 * been removed to keep this file focused.</p>
 *
 * <h2>Layers exercised</h2>
 * <ol>
 *   <li>{@link #directReadAfterSetFrame()} &mdash; Java-side {@code setFrame}
 *       writes followed by Java-side {@code frame()} producer evaluation.
 *       Must pass for the buffer model to be sane at the Java level.</li>
 *   <li>{@link #directReadAfterTick()} &mdash; running the {@code tick()}
 *       supplier from Java and re-evaluating the {@code frame()} producer.
 *       Must pass for tick to be writing where {@code frame()} reads.</li>
 *   <li>{@link #compiledLoopAdvancesFrame()} &mdash; ticking the clock inside
 *       a {@link TemporalRunner}-compiled loop and reading the final frame
 *       value from Java. If this fails, time storage is not live across
 *       compiled iterations of the same kernel.</li>
 *   <li>{@link #automationVariesWithDirectClock()} &mdash; reads
 *       {@code AutomationManager.getAggregatedValue} after manually setting
 *       the clock to several positions; verifies its evaluated value differs.
 *       Tests the producer chain {@code clock.frame() -> aggregated} outside
 *       any compiled loop.</li>
 *   <li>{@link #automationCapturedValueRespondsToClock()} &mdash; build the
 *       aggregated-value producer once, then advance the clock via direct
 *       Java {@code setFrame} calls, and re-evaluate the same producer
 *       instance. If the value doesn't change, the producer is somehow
 *       capturing a stale clock reference.</li>
 *   <li>{@link #automationCapturedValueRespondsToTick()} &mdash; same as
 *       above but advance via the clock's {@code tick()} runnable instead of
 *       {@code setFrame}.</li>
 * </ol>
 */
public class TimeCellAutomationTest extends TestSuiteBase implements CellFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	@Test(timeout = 30_000)
	public void directReadAfterSetFrame() {
		TimeCell clock = new TimeCell();
		clock.setup().get().run();

		Producer<PackedCollection> frame = clock.frame();

		clock.setFrame(0);
		assertEquals("frame() should reflect setFrame(0)",
				0.0, frame.get().evaluate().toDouble(0), 1e-9);

		clock.setFrame(1234);
		assertEquals("frame() should reflect setFrame(1234)",
				1234.0, frame.get().evaluate().toDouble(0), 1e-9);

		clock.setFrame(987654);
		assertEquals("frame() should reflect setFrame(987654)",
				987654.0, frame.get().evaluate().toDouble(0), 1e-9);
	}

	@Test(timeout = 30_000)
	public void directReadAfterTick() {
		TimeCell clock = new TimeCell();
		clock.setup().get().run();

		Producer<PackedCollection> frame = clock.frame();
		Runnable tick = clock.tick().get();

		double before = frame.get().evaluate().toDouble(0);
		tick.run();
		double afterOne = frame.get().evaluate().toDouble(0);
		assertEquals("frame() must advance by exactly 1 after one tick.run()",
				before + 1.0, afterOne, 1e-9);

		for (int i = 0; i < 99; i++) tick.run();
		double afterHundred = frame.get().evaluate().toDouble(0);
		assertEquals("frame() must advance by exactly 100 after 100 ticks",
				before + 100.0, afterHundred, 1e-9);
	}

	/**
	 * Drives the clock inside a {@link TemporalRunner}-compiled loop and reads
	 * the final frame from Java. The clock backs a single shared
	 * {@link PackedCollection}; if the runtime kernel reads/writes a different
	 * memory than the Java-side {@link TimeCell#getFrame()} accessor, the
	 * read-back will not match the iteration count.
	 */
	@Test(timeout = 60_000)
	public void compiledLoopAdvancesFrame() {
		TimeCell clock = new TimeCell();
		clock.setup().get().run();

		int iterations = 8192;
		TemporalRunner runner = new TemporalRunner(clock, iterations);
		runner.get().run();

		double observed = clock.getFrame();
		log("After " + iterations + " ticks in compiled loop, getFrame() = " + observed);
		assertEquals("After N ticks in compiled loop, getFrame must equal N",
				(double) iterations, observed, 1e-6);
	}

	@Test(timeout = 30_000)
	public void automationVariesWithDirectClock() {
		double measureDuration = Frequency.forBPM(120).l(4);
		GlobalTimeManager time = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(64);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(),
				() -> measureDuration, SAMPLE_RATE);

		PackedCollection params = new PackedCollection(64);
		params.randFill();
		genome.assignTo(params);

		OperationList setup = new OperationList("setup");
		setup.add(automation.setup());
		setup.add(time.setup());
		setup.get().run();

		Producer<PackedCollection> v = automation.getAggregatedValue(
				c(0.5), c(0.3), c(0.2),
				c(1.0), c(1.0), c(1.0),
				c(0.0));

		long[] frames = {0, 5L * SAMPLE_RATE, 30L * SAMPLE_RATE, 60L * SAMPLE_RATE};
		double[] samples = new double[frames.length];
		for (int i = 0; i < frames.length; i++) {
			time.getClock().setFrame(frames[i]);
			samples[i] = v.get().evaluate().toDouble(0);
			log("aggregated v @ frame " + frames[i] + " = " + samples[i]);
		}

		assertTrue("Aggregated v must differ between frame=0 and frame=60s "
						+ "(got " + samples[0] + " vs " + samples[samples.length - 1] + ")",
				samples[0] != samples[samples.length - 1]);
	}

	/**
	 * Builds the aggregated-value producer once and re-evaluates it after
	 * {@link TimeCell#setFrame(double)} writes between calls. Tests that the
	 * captured producer continues to read the live clock buffer.
	 */
	@Test(timeout = 30_000)
	public void automationCapturedValueRespondsToClock() {
		double measureDuration = Frequency.forBPM(120).l(4);
		GlobalTimeManager time = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(64);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(),
				() -> measureDuration, SAMPLE_RATE);

		PackedCollection params = new PackedCollection(64);
		params.randFill();
		genome.assignTo(params);

		OperationList setup = new OperationList("setup");
		setup.add(automation.setup());
		setup.add(time.setup());
		setup.get().run();

		// Build the aggregated-value producer ONCE, then change clock and
		// re-evaluate the SAME producer instance.
		Producer<PackedCollection> v = automation.getAggregatedValue(
				c(0.5), c(0.3), c(0.2),
				c(1.0), c(1.0), c(1.0),
				c(0.0));

		time.getClock().setFrame(0);
		double atZero = v.get().evaluate().toDouble(0);

		time.getClock().setFrame(60L * SAMPLE_RATE);
		double atSixty = v.get().evaluate().toDouble(0);

		log("captured v: t=0 -> " + atZero + ", t=60s -> " + atSixty);
		assertTrue("Captured aggregated-v producer should reflect post-build "
						+ "setFrame writes (got " + atZero + " vs " + atSixty + ")",
				atZero != atSixty);
	}

	/**
	 * Same as {@link #automationCapturedValueRespondsToClock()} but advances
	 * the clock by running its own {@link TimeCell#tick()} runnable instead
	 * of calling {@link TimeCell#setFrame(double)}. The tick path is what the
	 * real audio pipeline uses; if {@code setFrame} works but {@code tick}
	 * doesn't propagate, then the issue is that tick writes to a different
	 * buffer location than the producer reads.
	 */
	@Test(timeout = 30_000)
	public void automationCapturedValueRespondsToTick() {
		double measureDuration = Frequency.forBPM(120).l(4);
		GlobalTimeManager time = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(64);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(),
				() -> measureDuration, SAMPLE_RATE);

		PackedCollection params = new PackedCollection(64);
		params.randFill();
		genome.assignTo(params);

		OperationList setup = new OperationList("setup");
		setup.add(automation.setup());
		setup.add(time.setup());
		setup.get().run();

		Producer<PackedCollection> v = automation.getAggregatedValue(
				c(0.5), c(0.3), c(0.2),
				c(1.0), c(1.0), c(1.0),
				c(0.0));

		double initial = v.get().evaluate().toDouble(0);

		// Advance the clock by 60 seconds of frames via a *compiled* tick
		// loop — running 2.6M Java-side tick.run() invocations is too slow.
		int target = 60 * SAMPLE_RATE;
		new TemporalRunner(time.getClock(), target).get().run();

		double advanced = v.get().evaluate().toDouble(0);
		double frameNow = time.getClock().getFrame();
		log("captured v: initial=" + initial + ", after " + target + " ticks v="
				+ advanced + " (clock frame=" + frameNow + ")");

		// Sanity-check that the clock actually moved.
		assertTrue("Clock must have advanced by the expected number of ticks (was "
						+ frameNow + ")",
				frameNow >= target - 1);

		assertTrue("Captured aggregated-v producer should differ after the clock "
						+ "ticks by 60s of frames (initial=" + initial
						+ ", advanced=" + advanced + ")",
				initial != advanced);
	}
}
