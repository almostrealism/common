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

package org.almostrealism.studio.arrange;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Verifies that the per-channel coefficient banks {@link MixdownManagerPdslAdapter} builds over
 * all channels at once hold, row for row, the coefficients of each channel's own genes. The
 * banks are computed as one {@code [channels]}-wide graph so they compile once rather than once
 * per channel; these tests fail if that batching misorders channels, mixes their genes, or
 * selects the wrong response-table row.
 */
public class MixdownManagerPdslAdapterTest extends TestSuiteBase implements CellFeatures {

	/** Channels in the fixture; three, so a transposed or rotated bank cannot pass. */
	private static final int CHANNELS = 3;

	/** Sample rate of the fixture. */
	private static final int SAMPLE_RATE = 44100;

	/** FIR order of the fixture (the real-time runner's order). */
	private static final int FILTER_ORDER = 40;

	/** FIR taps per coefficient row. */
	private static final int TAPS = FILTER_ORDER + 1;

	/** Genome parameter count, oversized so any chromosome layout fits. */
	private static final int GENOME_PARAMS = 256;

	/** Rows of the adapter's response tables. */
	private static final int TABLE_BINS = 1024;

	/** Tolerance for float32 kernel output compared with a separately compiled reference. */
	private static final double EPS = 1e-5;

	/** The fixture's mixdown manager. */
	private MixdownManager mixdown;

	/** The fixture's effects manager. */
	private EfxManager efx;

	/** The fixture's adapter. */
	private MixdownManagerPdslAdapter adapter;

	/** The argument map the fixture's adapter built. */
	private Map<String, Object> args;

	/** The genome parameters the fixture assigned, released with the fixture. */
	private PackedCollection genomeParams;

	/** Restores the process-wide {@link MixdownManager} flags {@link #buildFixture(int)} sets. */
	private Runnable restoreFlags;

	/**
	 * Captures the {@link MixdownManager} flags the fixture changes, so {@link #restoreFlags()}
	 * leaves them as this class found them and later tests in the same JVM see no difference.
	 */
	@Before
	public void captureFlags() {
		boolean enableMainFilterUp = MixdownManager.enableMainFilterUp;
		boolean enableEfx = MixdownManager.enableEfx;
		boolean enableEfxFilters = MixdownManager.enableEfxFilters;
		boolean enableReverb = MixdownManager.enableReverb;
		boolean enableAutomationManager = MixdownManager.enableAutomationManager;
		double hpCutoffOverrideHz = MixdownManagerPdslAdapter.hpCutoffOverrideHz;
		restoreFlags = () -> {
			MixdownManagerPdslAdapter.hpCutoffOverrideHz = hpCutoffOverrideHz;
			MixdownManager.enableMainFilterUp = enableMainFilterUp;
			MixdownManager.enableEfx = enableEfx;
			MixdownManager.enableEfxFilters = enableEfxFilters;
			MixdownManager.enableReverb = enableReverb;
			MixdownManager.enableAutomationManager = enableAutomationManager;
		};
	}

	/**
	 * Releases the last fixture's native memory and restores the {@link MixdownManager} flags
	 * captured by {@link #captureFlags()}.
	 */
	@After
	public void restoreFlags() {
		Destroyable.releaseAll(List.of(this::releaseFixture, restoreFlags));
	}

	/**
	 * Releases the native memory the current fixture owns: every {@link PackedCollection} in the
	 * adapter's argument map, the mixdown manager and its dependencies, the effects manager's
	 * consolidated buffers, and the assigned genome parameters. Producer-valued arguments hold no
	 * memory of their own and are left to garbage collection. Does nothing when no fixture is built.
	 */
	private void releaseFixture() {
		if (args == null) return;

		List<Runnable> releases = new ArrayList<>();
		for (Object value : args.values()) {
			if (value instanceof PackedCollection) {
				releases.add(((PackedCollection) value)::destroy);
			}
		}
		releases.add(mixdown::destroy);
		releases.add(efx::destroyConsolidatedBuffers);
		releases.add(genomeParams::destroy);

		args = null;
		mixdown = null;
		efx = null;
		adapter = null;
		genomeParams = null;
		Destroyable.releaseAll(releases);
	}

	/**
	 * Each row of {@code efx_filter_coeffs} is the high-pass or low-pass windowed-sinc bank
	 * that channel's decision gene selects, at that channel's cutoff gene.
	 */
	@Test(timeout = 300_000)
	public void efxFilterBankMatchesPerChannelGenes() {
		buildFixture();
		Chromosome<PackedCollection> levels = efx.getDelayLevels();

		try (PackedCollection bank = evaluateArg("efx_filter_coeffs")) {
			for (int ch = 0; ch < CHANNELS; ch++) {
				Producer<PackedCollection> decision = levels.valueAt(ch, 2).getResultant(c(1.0));
				CollectionProducer sel = floor(multiply(min(decision, c(0.999999)), c(2.0)));
				Producer<PackedCollection> clamped = max(c(20.0), min(
						multiply(levels.valueAt(ch, 3).getResultant(c(1.0)), c(20000.0)),
						c(0.49 * SAMPLE_RATE)));
				try (PackedCollection expected = highPassCoefficients(clamped, SAMPLE_RATE, FILTER_ORDER)
						.multiply(c(1.0).subtract(sel))
						.add(lowPassCoefficients(clamped, SAMPLE_RATE, FILTER_ORDER).multiply(sel))
						.evaluate()) {
					assertRow("efx_filter_coeffs", bank, ch, expected, 0);
				}
			}
		}
	}

	/**
	 * Each row of {@code wet_hp_coeffs} and {@code wet_filter_coeffs} is the response-table row
	 * nearest that channel's wet-filter high-pass or low-pass cutoff gene.
	 */
	@Test(timeout = 300_000)
	public void wetFilterBanksMatchPerChannelGenes() {
		buildFixture();
		FixedFilterChromosome wetFilter = mixdown.getWetFilter();
		for (boolean high : new boolean[] { true, false }) {
			String key = high ? "wet_hp_coeffs" : "wet_filter_coeffs";
			try (PackedCollection bank = evaluateArg(key);
				 PackedCollection table = adapter.biquadResponseTable(high)) {
				for (int ch = 0; ch < CHANNELS; ch++) {
					double cutoff;
					try (PackedCollection bounded = bound(high ? wetFilter.highPassFrequency(ch)
									: wetFilter.lowPassFrequency(ch),
							AudioPassFilter.MIN_FREQUENCY, 20000).evaluate()) {
						cutoff = bounded.toDouble(0);
					}
					assertRow(key, bank, ch, table, bin(cutoff) * TAPS);
				}
			}
		}
	}

	/**
	 * Each row of the per-buffer {@code hp_coeffs} slot, as the automation refresh run by
	 * {@link MixdownManagerPdslAdapter#buildArgsMap()} leaves it, is the high-pass response-table
	 * row nearest the cutoff the same refresh wrote to that channel's {@code hp_cutoff} slot.
	 */
	@Test(timeout = 300_000)
	public void refreshedHighPassSlotMatchesCutoffSlot() {
		buildFixture();
		PackedCollection bank = (PackedCollection) args.get("hp_coeffs");
		PackedCollection cutoffs = (PackedCollection) args.get("hp_cutoff");
		try (PackedCollection table = adapter.biquadResponseTable(true)) {
			for (int ch = 0; ch < CHANNELS; ch++) {
				assertRow("hp_coeffs", bank, ch, table, bin(cutoffs.toDouble(ch)) * TAPS);
			}
		}
	}

	/**
	 * With {@link MixdownManagerPdslAdapter#hpCutoffOverrideHz} set, every row of the refreshed
	 * {@code hp_coeffs} slot is the response-table row of the override, bounded to the table's
	 * range exactly as a gene cutoff is: {@code 0} selects the lowest bin rather than an
	 * out-of-range row, {@code 30000} the highest, and {@code 2000} the bin nearest 2 kHz.
	 */
	@Test(timeout = 300_000)
	public void highPassOverrideSelectsBoundedTableRow() {
		double[] overrides = { 0.0, 2000.0, 30000.0 };
		int[] expectedBins = { 0, bin(2000.0), TABLE_BINS - 1 };
		for (int i = 0; i < overrides.length; i++) {
			MixdownManagerPdslAdapter.hpCutoffOverrideHz = overrides[i];
			buildFixture();
			PackedCollection bank = (PackedCollection) args.get("hp_coeffs");
			try (PackedCollection table = adapter.biquadResponseTable(true)) {
				for (int ch = 0; ch < CHANNELS; ch++) {
					assertRow("hp_coeffs(override=" + overrides[i] + ")", bank, ch, table,
							expectedBins[i] * TAPS);
				}
			}
		}
	}

	/**
	 * Compiling the gene-driven coefficient banks takes no more kernels for {@link #CHANNELS}
	 * channels than for one channel fewer. Built one subgraph per channel, each channel reads its
	 * genes at its own offset and compiles kernels of its own, so the count grows with the
	 * channel count — the cost that dominated building the real-time runner.
	 */
	@Test(timeout = 300_000)
	public void coefficientBanksCompileIndependentlyOfChannelCount() {
		long fewer = coefficientBankCompilations(CHANNELS - 1);
		long more = coefficientBankCompilations(CHANNELS);
		log("coefficient bank kernels: channels=" + (CHANNELS - 1) + " compiles=" + fewer
				+ " channels=" + CHANNELS + " compiles=" + more);
		Assert.assertTrue("Compiling the coefficient banks for " + CHANNELS + " channels took "
						+ more + " kernels, more than the " + fewer + " for " + (CHANNELS - 1),
				more <= fewer);
	}

	/**
	 * Builds a fixture with the given channel count and returns how many kernels evaluating its
	 * producer-valued coefficient banks compiled, on whichever backend is in use.
	 *
	 * @param channels the fixture's channel count
	 * @return the number of kernel compilations the evaluation caused
	 */
	private long coefficientBankCompilations(int channels) {
		buildFixture(channels);
		long before = HardwareOperator.cpuCompileCount + HardwareOperator.gpuCompileCount;
		for (String key : new String[] { "efx_filter_coeffs", "wet_hp_coeffs", "wet_filter_coeffs" }) {
			evaluateArg(key).destroy();
		}
		return HardwareOperator.cpuCompileCount + HardwareOperator.gpuCompileCount - before;
	}

	/** Builds the fixture with {@link #CHANNELS} channels; see {@link #buildFixture(int)}. */
	private void buildFixture() {
		buildFixture(CHANNELS);
	}

	/**
	 * Builds a mixdown and effects manager over one genome with seeded, varied parameters (so
	 * every channel's genes differ), runs their setup, and builds the wet-layer argument map.
	 * Any previous fixture is released first, so a test may build more than one.
	 *
	 * @param channels the number of channels
	 */
	private void buildFixture(int channels) {
		releaseFixture();

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		MixdownManager.enableAutomationManager = true;

		double measure = Frequency.forBPM(120).l(4);
		GlobalTimeManager time = new GlobalTimeManager(m -> (int) (m * measure * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(GENOME_PARAMS);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(), () -> measure, SAMPLE_RATE);
		mixdown = new MixdownManager(genome.addChromosome(), channels, channels,
				automation, time.getClock(), SAMPLE_RATE);
		efx = new EfxManager(genome.addChromosome(), channels, automation,
				() -> measure / 4.0, SAMPLE_RATE);
		genome.consolidateGeneValues();

		genomeParams = rand(shape(GENOME_PARAMS), new Random(58)).evaluate();
		genome.assignTo(genomeParams);

		OperationList setup = new OperationList("adapter fixture setup");
		setup.add(automation.setup());
		setup.add(mixdown.setup());
		setup.add(time.setup());
		setup.get().run();

		adapter = new MixdownManagerPdslAdapter(mixdown, efx,
				new MixdownManagerPdslAdapter.Config(channels, SAMPLE_RATE / 16, SAMPLE_RATE,
						FILTER_ORDER, 0.5, 2000));
		args = adapter.buildArgsMap();
	}

	/**
	 * Evaluates a producer-valued argument of the fixture's map.
	 *
	 * @param key the argument name
	 * @return the evaluated argument
	 */
	private PackedCollection evaluateArg(String key) {
		Producer<PackedCollection> value = (Producer<PackedCollection>) args.get(key);
		return value.evaluate();
	}

	/**
	 * Returns the response-table bin the adapter selects for a cutoff.
	 *
	 * @param cutoff the cutoff in Hz
	 * @return the bin index
	 */
	private static int bin(double cutoff) {
		double scale = (TABLE_BINS - 1) / Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
		return (int) Math.floor(scale * Math.log(cutoff / AudioPassFilter.MIN_FREQUENCY) + 0.5);
	}

	/**
	 * Asserts that one {@link #TAPS}-wide row of a coefficient bank equals {@link #TAPS} values
	 * of a reference starting at an offset.
	 *
	 * @param label     names the bank in a failure message
	 * @param bank      the {@code [channels, taps]} bank
	 * @param row       the channel row to check
	 * @param reference the reference values
	 * @param offset    where the expected row starts in {@code reference}
	 */
	private static void assertRow(String label, PackedCollection bank, int row,
								  PackedCollection reference, int offset) {
		for (int t = 0; t < TAPS; t++) {
			Assert.assertEquals(label + "[" + row + ", " + t + "]",
					reference.toDouble(offset + t), bank.toDouble(row * TAPS + t), EPS);
		}
	}
}
