/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.optimize.test;

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.jni.NativeComputeContext;
import org.almostrealism.music.pattern.PatternElementFactory;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.SilenceDurationHealthComputation;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioPopulationOptimizer;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the static-flag mapping that {@link AudioSceneOptimizer#setFeatureLevel(int)} and
 * {@link AudioSceneOptimizer#setVerbosity(int, boolean)} encode. These two methods are the single
 * place where a numeric "feature level" or "verbosity" is translated into the many processing and
 * logging switches scattered across the mixdown, EFX, pattern, and health subsystems; a regression
 * in that translation silently changes what every optimization run renders or logs.
 *
 * <p>Because the switches are process-global {@code static} fields, every field either method
 * touches is captured before each test and restored after it, so these tests neither depend on nor
 * leak global state.</p>
 */
public class AudioSceneOptimizerConfigTest extends TestSuiteBase {
	/** Saved {@code PatternElementFactory.enableVolumeEnvelope}. */
	private boolean volumeEnvelope;
	/** Saved {@code PatternElementFactory.enableFilterEnvelope}. */
	private boolean filterEnvelope;
	/** Saved {@code MixdownManager.disableClean}. */
	private boolean disableClean;
	/** Saved {@code MixdownManager.enableSourcesOnly}. */
	private boolean sourcesOnly;
	/** Saved {@code EfxManager.enableEfx}. */
	private boolean efxManagerEfx;
	/** Saved {@code MixdownManager.enableEfx}. */
	private boolean mixEfx;
	/** Saved {@code MixdownManager.enableEfxFilters}. */
	private boolean mixEfxFilters;
	/** Saved {@code MixdownManager.enableTransmission}. */
	private boolean transmission;
	/** Saved {@code MixdownManager.enableMainFilterUp}. */
	private boolean mainFilterUp;
	/** Saved {@code MixdownManager.enableAutomationManager}. */
	private boolean automationManager;
	/** Saved {@code MixdownManager.enableWetInAdjustment}. */
	private boolean wetInAdjustment;
	/** Saved {@code MixdownManager.enableMasterFilterDown}. */
	private boolean masterFilterDown;
	/** Saved {@code MixdownManager.enableReverb}. */
	private boolean reverb;
	/** Saved {@code StableDurationHealthComputation.enableTimeout}. */
	private boolean timeout;
	/** Saved {@code SilenceDurationHealthComputation.enableSilenceCheck}. */
	private boolean silenceCheck;
	/** Saved {@code AudioPopulationOptimizer.enableStemOutput}. */
	private boolean stemOutput;
	/** Saved {@code PopulationOptimizer.enableBreeding}. */
	private boolean breeding;
	/** Saved {@code PopulationOptimizer.enableVerbose}. */
	private boolean optimizerVerbose;
	/** Saved {@code NoteAudioProvider.enableVerbose}. */
	private boolean noteVerbose;
	/** Saved {@code AudioSceneOptimizer.enableVerbose}. */
	private boolean sceneOptimizerVerbose;
	/** Saved {@code SilenceDurationHealthComputation.enableVerbose}. */
	private boolean silenceVerbose;
	/** Saved {@code StableDurationHealthComputation.enableProfileAutosave}. */
	private boolean profileAutosave;
	/** Saved {@code PatternSystemManager.enableVerbose}. */
	private boolean patternVerbose;
	/** Saved {@code PopulationOptimizer.enableDisplayGenomes}. */
	private boolean displayGenomes;
	/** Saved {@code Hardware.enableVerbose}. */
	private boolean hardwareVerbose;
	/** Saved {@code HardwareOperator.enableLog}. */
	private boolean hardwareLog;
	/** Saved {@code HardwareOperator.enableVerboseLog}. */
	private boolean hardwareVerboseLog;
	/** Saved {@code WaveOutput.enableVerbose}. */
	private boolean waveOutputVerbose;
	/** Saved {@code NativeComputeContext.enableVerbose}. */
	private boolean nativeContextVerbose;
	/** Saved {@code StableDurationHealthComputation.profile}, also the profile assigned to the hardware. */
	private OperationProfile healthProfile;

	/** Captures all global flags the methods under test mutate. */
	@Before
	public void captureFlags() {
		volumeEnvelope = PatternElementFactory.enableVolumeEnvelope;
		filterEnvelope = PatternElementFactory.enableFilterEnvelope;
		disableClean = MixdownManager.disableClean;
		sourcesOnly = MixdownManager.enableSourcesOnly;
		efxManagerEfx = EfxManager.enableEfx;
		mixEfx = MixdownManager.enableEfx;
		mixEfxFilters = MixdownManager.enableEfxFilters;
		transmission = MixdownManager.enableTransmission;
		mainFilterUp = MixdownManager.enableMainFilterUp;
		automationManager = MixdownManager.enableAutomationManager;
		wetInAdjustment = MixdownManager.enableWetInAdjustment;
		masterFilterDown = MixdownManager.enableMasterFilterDown;
		reverb = MixdownManager.enableReverb;
		timeout = StableDurationHealthComputation.enableTimeout;
		silenceCheck = SilenceDurationHealthComputation.enableSilenceCheck;
		stemOutput = AudioPopulationOptimizer.enableStemOutput;
		breeding = PopulationOptimizer.enableBreeding;
		optimizerVerbose = PopulationOptimizer.enableVerbose;
		noteVerbose = NoteAudioProvider.enableVerbose;
		sceneOptimizerVerbose = AudioSceneOptimizer.enableVerbose;
		silenceVerbose = SilenceDurationHealthComputation.enableVerbose;
		profileAutosave = StableDurationHealthComputation.enableProfileAutosave;
		patternVerbose = PatternSystemManager.enableVerbose;
		displayGenomes = PopulationOptimizer.enableDisplayGenomes;
		hardwareVerbose = Hardware.enableVerbose;
		hardwareLog = HardwareOperator.enableLog;
		hardwareVerboseLog = HardwareOperator.enableVerboseLog;
		waveOutputVerbose = WaveOutput.enableVerbose;
		nativeContextVerbose = NativeComputeContext.enableVerbose;
		healthProfile = StableDurationHealthComputation.profile;
	}

	/** Restores all captured global flags. */
	@After
	public void restoreFlags() {
		PatternElementFactory.enableVolumeEnvelope = volumeEnvelope;
		PatternElementFactory.enableFilterEnvelope = filterEnvelope;
		MixdownManager.disableClean = disableClean;
		MixdownManager.enableSourcesOnly = sourcesOnly;
		EfxManager.enableEfx = efxManagerEfx;
		MixdownManager.enableEfx = mixEfx;
		MixdownManager.enableEfxFilters = mixEfxFilters;
		MixdownManager.enableTransmission = transmission;
		MixdownManager.enableMainFilterUp = mainFilterUp;
		MixdownManager.enableAutomationManager = automationManager;
		MixdownManager.enableWetInAdjustment = wetInAdjustment;
		MixdownManager.enableMasterFilterDown = masterFilterDown;
		MixdownManager.enableReverb = reverb;
		StableDurationHealthComputation.enableTimeout = timeout;
		SilenceDurationHealthComputation.enableSilenceCheck = silenceCheck;
		AudioPopulationOptimizer.enableStemOutput = stemOutput;
		PopulationOptimizer.enableBreeding = breeding;
		PopulationOptimizer.enableVerbose = optimizerVerbose;
		NoteAudioProvider.enableVerbose = noteVerbose;
		AudioSceneOptimizer.enableVerbose = sceneOptimizerVerbose;
		SilenceDurationHealthComputation.enableVerbose = silenceVerbose;
		StableDurationHealthComputation.enableProfileAutosave = profileAutosave;
		PatternSystemManager.enableVerbose = patternVerbose;
		PopulationOptimizer.enableDisplayGenomes = displayGenomes;
		Hardware.enableVerbose = hardwareVerbose;
		HardwareOperator.enableLog = hardwareLog;
		HardwareOperator.enableVerboseLog = hardwareVerboseLog;
		WaveOutput.enableVerbose = waveOutputVerbose;
		NativeComputeContext.enableVerbose = nativeContextVerbose;
		StableDurationHealthComputation.profile = healthProfile;
		Hardware.getLocalHardware().assignProfile(healthProfile);
	}

	/** The full feature level (7) enables every processing stage, up to and including reverb. */
	@Test(timeout = 60000)
	public void fullFeatureLevelEnablesEveryStage() {
		AudioSceneOptimizer.setFeatureLevel(7);

		Assert.assertTrue(PatternElementFactory.enableVolumeEnvelope);
		Assert.assertTrue(PatternElementFactory.enableFilterEnvelope);
		Assert.assertTrue(EfxManager.enableEfx);
		Assert.assertTrue(MixdownManager.enableEfx);
		Assert.assertTrue(MixdownManager.enableEfxFilters);
		Assert.assertTrue(MixdownManager.enableTransmission);
		Assert.assertTrue(MixdownManager.enableMainFilterUp);
		Assert.assertTrue(MixdownManager.enableAutomationManager);
		Assert.assertTrue(MixdownManager.enableWetInAdjustment);
		Assert.assertTrue(MixdownManager.enableMasterFilterDown);
		Assert.assertTrue("reverb requires level > 6", MixdownManager.enableReverb);
		Assert.assertTrue("stem output is enabled at every non-negative feature level",
				AudioPopulationOptimizer.enableStemOutput);
		Assert.assertFalse("sources-only is a negative-level mode", MixdownManager.enableSourcesOnly);
		Assert.assertFalse(StableDurationHealthComputation.enableTimeout);
		Assert.assertFalse(SilenceDurationHealthComputation.enableSilenceCheck);
	}

	/**
	 * Level 5 sits just below the wet-in / master-filter-down boundary: the main filter sweep is on
	 * (gated above 4) while {@code enableWetInAdjustment} and {@code enableMasterFilterDown} remain
	 * off because both require a level strictly above 5. Reverb (above 6) is off, and stem output is
	 * on as it is at every non-negative level. This pins the strict {@code > 5} threshold that the
	 * level-7 test only exercises from the enabled side.
	 */
	@Test(timeout = 60000)
	public void featureLevelFiveLeavesWetAndMasterFilterOff() {
		AudioSceneOptimizer.setFeatureLevel(5);

		Assert.assertTrue("main filter sweep enables above level 4", MixdownManager.enableMainFilterUp);
		Assert.assertFalse("wet-in adjustment requires above level 5", MixdownManager.enableWetInAdjustment);
		Assert.assertFalse("master filter down requires above level 5", MixdownManager.enableMasterFilterDown);
		Assert.assertFalse("reverb requires above level 6", MixdownManager.enableReverb);
		Assert.assertTrue(AudioPopulationOptimizer.enableStemOutput);
	}

	/**
	 * At the minimal feature level (0) the envelope stages are still off (they require a level
	 * strictly above zero), and every stage gated above level 0 — EFX, transmission, reverb — is
	 * disabled. Sources-only remains off because it is reserved for negative levels.
	 */
	@Test(timeout = 60000)
	public void minimalFeatureLevelDisablesEffectStages() {
		AudioSceneOptimizer.setFeatureLevel(0);

		Assert.assertFalse(PatternElementFactory.enableVolumeEnvelope);
		Assert.assertFalse(PatternElementFactory.enableFilterEnvelope);
		Assert.assertFalse(EfxManager.enableEfx);
		Assert.assertFalse(MixdownManager.enableEfx);
		Assert.assertFalse(MixdownManager.enableEfxFilters);
		Assert.assertFalse(MixdownManager.enableTransmission);
		Assert.assertFalse(MixdownManager.enableMainFilterUp);
		Assert.assertFalse(MixdownManager.enableAutomationManager);
		Assert.assertFalse(MixdownManager.enableReverb);
		Assert.assertFalse(MixdownManager.enableSourcesOnly);
	}

	/**
	 * Level 3 sits on the EFX boundary: EFX itself and its filters are on (gated above 2) while
	 * transmission (above 3), the main filter sweep (above 4) and reverb (above 6) remain off. This
	 * pins that the thresholds are strict ({@code >}), not inclusive.
	 */
	@Test(timeout = 60000)
	public void midFeatureLevelCrossesExactlyTheExpectedThresholds() {
		AudioSceneOptimizer.setFeatureLevel(3);

		Assert.assertTrue("EFX enables above level 2", MixdownManager.enableEfx);
		Assert.assertTrue(MixdownManager.enableEfxFilters);
		Assert.assertFalse("transmission requires above level 3", MixdownManager.enableTransmission);
		Assert.assertFalse("main filter sweep requires above level 4", MixdownManager.enableMainFilterUp);
		Assert.assertFalse(MixdownManager.enableAutomationManager);
		Assert.assertFalse(MixdownManager.enableReverb);
	}

	/** A negative feature level selects the sources-only rendering mode. */
	@Test(timeout = 60000)
	public void negativeFeatureLevelSelectsSourcesOnly() {
		AudioSceneOptimizer.setFeatureLevel(-1);

		Assert.assertTrue(MixdownManager.enableSourcesOnly);
		Assert.assertFalse("envelopes are off below level 1", PatternElementFactory.enableVolumeEnvelope);
	}

	/**
	 * The verbosity sign carries the breeding switch: a negative verbosity <em>enables</em>
	 * breeding ({@code enableBreeding = verbosity < 0}). It is below every subsystem logging
	 * threshold, and with profiling off no profile node is created or assigned to the health
	 * computation.
	 */
	@Test(timeout = 60000)
	public void negativeVerbosityEnablesBreedingAndCreatesNoProfile() {
		OperationProfileNode profile = AudioSceneOptimizer.setVerbosity(-1, false);

		Assert.assertNull("no profile must be created when profiling is off", profile);
		Assert.assertTrue("a negative verbosity enables breeding", PopulationOptimizer.enableBreeding);
		Assert.assertNull(StableDurationHealthComputation.profile);
		Assert.assertFalse("level -1 is below the optimizer verbose threshold",
				PopulationOptimizer.enableVerbose);
	}

	/**
	 * A verbosity above the subsystem thresholds turns on the optimizer's own verbose logging and,
	 * when profiling is requested, both returns a profile node and wires it into the health
	 * computation. A non-negative verbosity also disables breeding (the inverse of the negative
	 * case above).
	 */
	@Test(timeout = 60000)
	public void highVerbosityEnablesLoggingAndProfileButDisablesBreeding() {
		OperationProfileNode profile = AudioSceneOptimizer.setVerbosity(3, true);

		Assert.assertNotNull("a profile node must be created when profiling is requested", profile);
		Assert.assertFalse("a non-negative verbosity disables breeding", PopulationOptimizer.enableBreeding);
		Assert.assertTrue("level 3 is above the optimizer verbose threshold",
				PopulationOptimizer.enableVerbose);
		Assert.assertSame("the created profile must be assigned to the health computation",
				profile, StableDurationHealthComputation.profile);
	}

	/**
	 * Verbosity 4 crosses the level-4 logging threshold but not the level-5 one: the wave-output and
	 * native-context logs turn on (both gated above 3) while {@code HardwareOperator.enableVerboseLog}
	 * stays off because it requires a verbosity strictly above 4. This pins the strict {@code > 4}
	 * boundary separating those two bands.
	 */
	@Test(timeout = 60000)
	public void verbosityFourEnablesWaveOutputButNotVerboseLog() {
		AudioSceneOptimizer.setVerbosity(4, false);

		Assert.assertTrue("wave output logging enables above verbosity 3", WaveOutput.enableVerbose);
		Assert.assertTrue("native context logging enables above verbosity 3",
				NativeComputeContext.enableVerbose);
		Assert.assertFalse("verbose operator logging requires above verbosity 4",
				HardwareOperator.enableVerboseLog);
	}

	/**
	 * Verbosity 5 is above every logging threshold, so the highest-band switches the lower-verbosity
	 * tests never reach are all on: the wave-output and native-context logs (above 3), the verbose
	 * operator log (above 4), and the level-3 operator/hardware/pattern logs (above 2). This pins the
	 * top of the verbosity-to-logging mapping, which the verbosity-3 test leaves unverified.
	 */
	@Test(timeout = 60000)
	public void veryHighVerbosityEnablesEveryLoggingThreshold() {
		AudioSceneOptimizer.setVerbosity(5, false);

		Assert.assertTrue("wave output logging enables above verbosity 3", WaveOutput.enableVerbose);
		Assert.assertTrue("native context logging enables above verbosity 3",
				NativeComputeContext.enableVerbose);
		Assert.assertTrue("verbose operator logging enables above verbosity 4",
				HardwareOperator.enableVerboseLog);
		Assert.assertTrue("operator logging enables above verbosity 2", HardwareOperator.enableLog);
		Assert.assertTrue("hardware verbose logging enables above verbosity 2", Hardware.enableVerbose);
		Assert.assertTrue("pattern system logging enables above verbosity 2",
				PatternSystemManager.enableVerbose);
	}
}
