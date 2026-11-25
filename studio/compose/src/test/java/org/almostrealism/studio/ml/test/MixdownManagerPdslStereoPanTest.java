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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.studio.dsl.audio.AudioDspPrimitives;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * Verifies the true-stereo, per-channel-pan behaviour of the PDSL mixdown. Each stereo
 * side is rendered by its own {@link MixdownManagerPdslAdapter} (built with a
 * {@link ChannelInfo.StereoChannel}), which folds that side's balance-law gain
 * ({@code gL(p) = min(1, 1 − p)}, {@code gR(p) = min(1, 1 + p)}) into the channel's dry
 * volume and its sends. The tests assert the two consequences that matter:
 *
 * <ul>
 *   <li><b>Hard-pan energy split.</b> With a channel hard-panned, that channel's energy
 *       reaches only its side; the opposite side is silent. At centre both sides carry
 *       equal energy. This holds with effects on ({@code mixdown_master_wet}) and off
 *       ({@code mixdown_master}), so the dry bus, the efx bus and the reverb send are all
 *       panned.</li>
 *   <li><b>Centre parity.</b> At {@code pan = 0} each side's gain is exactly {@code 1}, so
 *       a centred side reproduces the mono (no-side) layer bit-for-bit — the existing
 *       mono mixdown tests keep their meaning.</li>
 * </ul>
 *
 * @see MixdownManagerPdslAdapter
 * @see MixdownManager#setPan(int, double)
 */
public class MixdownManagerPdslStereoPanTest extends TestSuiteBase
		implements MixdownPdslTestFeatures {

	/** Channels in the fixture mixdown. */
	private static final int CHANNELS = 2;

	/** Delay layers in the fixture mixdown (square routing). */
	private static final int DELAY_LAYERS = CHANNELS;

	/** Samples per forward pass. */
	private static final int SIGNAL_SIZE = SAMPLE_RATE / 16;

	/** Genome parameter count — oversized so any chromosome layout fits. */
	private static final int GENOME_PARAMS = 256;

	/** The channel that is hard-panned in the split tests; the others stay silent. */
	private static final int PANNED_CHANNEL = 0;

	/** Automation manager of the most recently built fixture. */
	private AutomationManager fixtureAutomation;

	/** Time manager of the most recently built fixture. */
	private GlobalTimeManager fixtureTime;

	/** Effects manager of the most recently built fixture, when requested. */
	private EfxManager fixtureEfx;

	/**
	 * A channel hard-panned left reaches only the left master; the right master is silent.
	 * Panned right it mirrors; centred the two sides carry equal energy. Exercises the
	 * effects-on layer, so the dry bus, efx bus and reverb send are all panned. An
	 * intermediate pan ({@code ±0.5}) additionally pins the law shape: both sides stay
	 * audible and their energies must follow the squared balance-law gain ratio.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void hardPanSplitsEnergyWithEffects() {
		MixdownManager mixdown = buildFixture(true);

		assertSplit(mixdown, true, -1.0, true);
		assertSplit(mixdown, true, +1.0, false);
		assertCentreBalanced(mixdown, true);
		assertPanRatio(mixdown, true, +0.5);
		assertPanRatio(mixdown, true, -0.5);
	}

	/**
	 * The same energy split with effects off ({@code mixdown_master}), where the channel's
	 * pan is carried by its dry volume and its wet-filter coefficient row rather than a
	 * per-channel send.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void hardPanSplitsEnergyWithoutEffects() {
		MixdownManager mixdown = buildFixture(false);

		assertSplit(mixdown, false, -1.0, true);
		assertSplit(mixdown, false, +1.0, false);
		assertCentreBalanced(mixdown, false);
		assertPanRatio(mixdown, false, +0.5);
		assertPanRatio(mixdown, false, -0.5);
	}

	/**
	 * At {@code pan = 0} a LEFT-side adapter and a mono (no-side) adapter produce identical
	 * output for both layers — the centre-parity property the mono mixdown tests rely on.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void centrePanMatchesMonoLayer() {
		assertCentreParity(buildFixture(true), true);
		assertCentreParity(buildFixture(false), false);
	}

	/**
	 * Per-channel pan storage round-trips through {@link MixdownManager#getPan(int)} and is
	 * clamped to {@code [-1, 1]}, so a setting out of range cannot drive a side gain outside
	 * the balance law. Default is centre.
	 */
	@Test(timeout = 60_000)
	public void panStorageRoundTripsAndClamps() {
		MixdownManager mixdown = buildFixture(false);

		Assert.assertEquals("pan defaults to centre", 0.0, mixdown.getPan(0), 1e-9);

		mixdown.setPan(0, -0.4);
		Assert.assertEquals("pan must round-trip", -0.4, mixdown.getPan(0), 1e-9);

		mixdown.setPan(1, 2.0);
		Assert.assertEquals("pan above 1 must clamp to +1", 1.0, mixdown.getPan(1), 1e-9);

		mixdown.setPan(0, -5.0);
		Assert.assertEquals("pan below -1 must clamp to -1", -1.0, mixdown.getPan(0), 1e-9);
	}

	/**
	 * Builds a side's compiled mixdown over the fixture, feeds {@code input}, and returns
	 * the energy of the last of two passes (two so the ring state is engaged).
	 */
	private double sideEnergy(MixdownManager mixdown, boolean withEffects,
							  ChannelInfo.StereoChannel side, PackedCollection input) {
		int rows = withEffects ? 2 * CHANNELS : CHANNELS;
		TraversalPolicy inputShape = new TraversalPolicy(rows, SIGNAL_SIZE);
		String layer = withEffects ? "mixdown_master_wet" : "mixdown_master";

		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				WET_LEVEL, PDSL_DELAY_SAMPLES);
		MixdownManagerPdslAdapter adapter = new MixdownManagerPdslAdapter(
				mixdown, withEffects ? fixtureEfx : null, config, side);
		Map<String, Object> args = adapter.buildArgsMap();

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		Block block = loader.buildLayer(program, layer, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile(false);

		compiled.forward(input);
		PackedCollection out = compiled.forward(input);
		return energy(out.range(shape(SIGNAL_SIZE)), 0);
	}

	/**
	 * Asserts that hard-panning {@link #PANNED_CHANNEL} routes its energy entirely to one
	 * side. {@code leftLoud} selects which side must carry the energy ({@code true} for the
	 * left master, i.e. {@code pan = −1}).
	 */
	private void assertSplit(MixdownManager mixdown, boolean withEffects,
							 double pan, boolean leftLoud) {
		setPan(mixdown, pan);
		PackedCollection input = pannedChannelInput(withEffects);

		double leftEnergy = sideEnergy(mixdown, withEffects, ChannelInfo.StereoChannel.LEFT, input);
		double rightEnergy = sideEnergy(mixdown, withEffects, ChannelInfo.StereoChannel.RIGHT, input);

		double loud = leftLoud ? leftEnergy : rightEnergy;
		double quiet = leftLoud ? rightEnergy : leftEnergy;
		log(String.format("pan=%.1f effects=%b leftE=%.6g rightE=%.6g", pan, withEffects,
				leftEnergy, rightEnergy));

		Assert.assertTrue("the panned-to side must carry energy (loud=" + loud + ")",
				loud > 1e-8);
		Assert.assertTrue("the opposite side must be silent (quiet=" + quiet + ", loud="
				+ loud + ")", quiet < 1e-6 * loud);
	}

	/**
	 * Asserts that at an intermediate pan the two sides' energies follow the squared
	 * balance-law gain ratio {@code (gL/gR)^2}. The {@link #assertSplit} cases only cover
	 * {@code pan ∈ {0, ±1}}, where {@code g ∈ {0, 1}} and therefore {@code g^2 == g}, so
	 * they cannot tell a single gain application (energy {@code ∝ g^2}) from a double-count
	 * (energy {@code ∝ g^4}) or a differently-shaped law. A pan with {@code g ∉ {0, 1}}
	 * does: at {@code pan = +0.5} the law gives {@code gL = 0.5}, {@code gR = 1}, so the
	 * left/right energy ratio is {@code 0.25}, which a double-count would drive to
	 * {@code 0.0625}. A low-amplitude tone keeps the whole chain below its {@code clip}
	 * limiter, so both sides stay linear and the ratio is exactly the squared gain ratio.
	 */
	private void assertPanRatio(MixdownManager mixdown, boolean withEffects, double pan) {
		setPan(mixdown, pan);
		PackedCollection input = pannedChannelInput(withEffects, 0.02);

		double leftEnergy = sideEnergy(mixdown, withEffects, ChannelInfo.StereoChannel.LEFT, input);
		double rightEnergy = sideEnergy(mixdown, withEffects, ChannelInfo.StereoChannel.RIGHT, input);

		double gainLeft = Math.min(1.0, 1.0 - pan);
		double gainRight = Math.min(1.0, 1.0 + pan);
		double expected = (gainLeft * gainLeft) / (gainRight * gainRight);
		double actual = leftEnergy / rightEnergy;
		log(String.format("pan=%.2f effects=%b leftE=%.6g rightE=%.6g ratio=%.4f expected=%.4f",
				pan, withEffects, leftEnergy, rightEnergy, actual, expected));

		Assert.assertTrue("both sides must stay audible at an intermediate pan "
				+ "(leftE=" + leftEnergy + ", rightE=" + rightEnergy + ")",
				leftEnergy > 1e-10 && rightEnergy > 1e-10);
		Assert.assertEquals("intermediate pan must follow the squared balance-law gain ratio",
				expected, actual, 0.1 * expected);
	}

	/**
	 * Asserts that at {@code pan = 0} the two sides carry equal energy (both gains are 1).
	 */
	private void assertCentreBalanced(MixdownManager mixdown, boolean withEffects) {
		setPan(mixdown, 0.0);
		PackedCollection input = pannedChannelInput(withEffects);

		double leftEnergy = sideEnergy(mixdown, withEffects, ChannelInfo.StereoChannel.LEFT, input);
		double rightEnergy = sideEnergy(mixdown, withEffects, ChannelInfo.StereoChannel.RIGHT, input);

		Assert.assertTrue("centre must be non-silent", leftEnergy > 1e-8);
		Assert.assertEquals("centre pan must balance the two sides",
				leftEnergy, rightEnergy, 1e-6 * leftEnergy);
	}

	/**
	 * Asserts that a centred LEFT-side adapter and a mono (no-side) adapter produce
	 * identical output for the given layer.
	 */
	private void assertCentreParity(MixdownManager mixdown, boolean withEffects) {
		setPan(mixdown, 0.0);
		PackedCollection input = pannedChannelInput(withEffects);

		int rows = withEffects ? 2 * CHANNELS : CHANNELS;
		TraversalPolicy inputShape = new TraversalPolicy(rows, SIGNAL_SIZE);
		String layer = withEffects ? "mixdown_master_wet" : "mixdown_master";
		EfxManager efx = withEffects ? fixtureEfx : null;

		PackedCollection mono = renderOnce(mixdown, efx, layer, inputShape, null, input);
		PackedCollection left = renderOnce(mixdown, efx, layer, inputShape,
				ChannelInfo.StereoChannel.LEFT, input);

		double diff = energy(cp(left.range(shape(SIGNAL_SIZE)))
				.subtract(cp(mono.range(shape(SIGNAL_SIZE)))).evaluate(), 0);
		double ref = energy(mono.range(shape(SIGNAL_SIZE)), 0);
		log(String.format("centre-parity effects=%b diff=%.6g ref=%.6g", withEffects, diff, ref));
		Assert.assertTrue("centred LEFT side must match the mono layer (diff=" + diff
				+ ", ref=" + ref + ")", diff <= 1e-9 * Math.max(ref, 1e-12));
	}

	/**
	 * Compiles one adapter (with the given side, or {@code null} for mono) and returns a
	 * copy of a single forward's output.
	 */
	private PackedCollection renderOnce(MixdownManager mixdown, EfxManager efx, String layer,
										TraversalPolicy inputShape, ChannelInfo.StereoChannel side,
										PackedCollection input) {
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				WET_LEVEL, PDSL_DELAY_SAMPLES);
		MixdownManagerPdslAdapter adapter =
				new MixdownManagerPdslAdapter(mixdown, efx, config, side);
		Map<String, Object> args = adapter.buildArgsMap();

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		Block block = loader.buildLayer(program, layer, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile(false);

		PackedCollection copy = new PackedCollection(SIGNAL_SIZE);
		copy.setFrom(0, compiled.forward(input).range(shape(SIGNAL_SIZE)));
		return copy;
	}

	/**
	 * Builds an input whose {@link #PANNED_CHANNEL} carries a tone on both its MAIN row and
	 * (when effects are on) its WET row, with every other row silent, so the energy that
	 * reaches each side comes from that one channel.
	 */
	private PackedCollection pannedChannelInput(boolean withEffects) {
		return pannedChannelInput(withEffects, 0.5);
	}

	/**
	 * As {@link #pannedChannelInput(boolean)}, but with an explicit tone amplitude. A small
	 * amplitude keeps the mixdown chain below its {@code clip} limiter, so the ratio tests
	 * observe the linear (squared gain) relationship between the two sides.
	 */
	private PackedCollection pannedChannelInput(boolean withEffects, double amplitude) {
		int rows = withEffects ? 2 * CHANNELS : CHANNELS;
		PackedCollection input = new PackedCollection(new TraversalPolicy(rows, SIGNAL_SIZE));
		PackedCollection tone = sin(integers(0, SIGNAL_SIZE)
				.multiply(2.0 * Math.PI * 220.0 / SAMPLE_RATE)).multiply(amplitude).evaluate();
		input.setFrom(PANNED_CHANNEL * SIGNAL_SIZE, tone);
		if (withEffects) {
			input.setFrom((CHANNELS + PANNED_CHANNEL) * SIGNAL_SIZE, tone);
		}
		return input;
	}

	/** Sets the panned channel's pan and leaves every other channel centred. */
	private void setPan(MixdownManager mixdown, double pan) {
		for (int ch = 0; ch < CHANNELS; ch++) {
			mixdown.setPan(ch, ch == PANNED_CHANNEL ? pan : 0.0);
		}
	}

	/**
	 * Builds a fixture mixdown with a 0.5-filled genome, optionally with an effects
	 * manager, and runs the manager setup so the adapter's gene/automation producers read
	 * initialised state.
	 */
	private MixdownManager buildFixture(boolean withEfx) {
		MixdownManager.enableReverb = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableTransmission = true;
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableMasterFilterDown = true;
		MixdownManager.enableWetInAdjustment = false;
		MixdownManager.enableAutomationManager = true;

		double measureDuration = Frequency.forBPM(120).l(4);
		fixtureTime = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(GENOME_PARAMS);
		fixtureAutomation = new AutomationManager(genome.addChromosome(),
				fixtureTime.getClock(), () -> measureDuration, SAMPLE_RATE);
		MixdownManager mixdown = new MixdownManager(genome.addChromosome(),
				CHANNELS, DELAY_LAYERS, fixtureAutomation, fixtureTime.getClock(), SAMPLE_RATE);
		fixtureEfx = withEfx
				? new EfxManager(genome.addChromosome(), CHANNELS, fixtureAutomation,
						() -> measureDuration / 4.0, SAMPLE_RATE)
				: null;
		genome.consolidateGeneValues();
		genome.assignTo(new PackedCollection(GENOME_PARAMS).fill(0.5));

		// Route the panned channel through the reverb bus so the reverb send is panned too.
		mixdown.getReverbChannels().add(PANNED_CHANNEL);

		OperationList setup = new OperationList("stereo fixture setup");
		setup.add(fixtureAutomation.setup());
		setup.add(mixdown.setup());
		setup.add(fixtureTime.setup());
		setup.get().run();
		return mixdown;
	}
}
