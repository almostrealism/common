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

package org.almostrealism.studio.optimize;

import io.almostrealism.code.DataContext;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.filter.AudioSumProvider;
import org.almostrealism.studio.health.AudioHealthComputation;
import org.almostrealism.studio.health.HealthComputationAdapter;
import org.almostrealism.studio.health.SilenceDurationHealthComputation;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.music.pattern.PatternElementFactory;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.jni.NativeComputeContext;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataReplacementMap;
import org.almostrealism.heredity.Breeders;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.GenomeBreeder;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.optimize.PopulationOptimizer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * High-level optimizer that wires an {@link AudioScene} to a population of genomes and
 * coordinates evolutionary optimization cycles. Extends {@link AudioPopulationOptimizer} with
 * {@link TemporalCellular} as the temporal type.
 *
 * <p>On each cycle the population's genomes are applied to the scene, health is evaluated
 * by the configured {@link AudioHealthComputation}, and the resulting scored population is bred
 * to produce improved candidates.</p>
 */
public class AudioSceneOptimizer extends AudioPopulationOptimizer<TemporalCellular> {
	/** Local destination path for the JSON population persistence file. */
	public static final String POPULATION_FILE = SystemUtils.getLocalDestination("population.json");

	/** Verbosity level read from the {@code AR_AUDIO_OPT_VERBOSITY} system property. */
	public static final int verbosity = SystemUtils.getInt("AR_AUDIO_OPT_VERBOSITY").orElse(0);

	/** When set to {@code -1} all channels are active; otherwise only the specified channel is active. */
	public static final int singleChannel = -1;

	/** Enables verbose logging within the optimizer when {@code true}. */
	public static boolean enableVerbose = false;

	/** Enables hardware-level operation profiling when {@code true}. */
	public static boolean enableProfile = false;

	/** Enables native heap allocation for the optimization run when {@code true}. */
	public static boolean enableHeap = false;

	/** Reduces scene duration for faster iteration cycles when {@code true}. */
	public static boolean enableShort = false;

	/** Default native heap size in bytes when {@link #enableHeap} is active. */
	public static int DEFAULT_HEAP_SIZE = 384 * 1024 * 1024;

	/** Magnitude of random perturbations applied during genome breeding. */
	public static double breederPerturbation = 0.02;

	/** Root directory path of the audio sample library, configurable via {@code AR_RINGS_LIBRARY}. */
	public static String LIBRARY = SystemUtils.getProperty("AR_RINGS_LIBRARY", "Library");

	/** The active audio scene population managed by this optimizer. */
	private AudioScenePopulation population;

	/** Optional processor for wave detail metadata produced during health evaluation. */
	private Consumer<WaveDetails> detailsProcessor;

	/**
	 * Constructs an {@code AudioSceneOptimizer} for the given audio scene, using the default
	 * genome generator derived from the scene's genome factory.
	 *
	 * @param scene        the audio scene whose parameters will be optimized
	 * @param breeder      supplier of the genome breeder for producing offspring
	 * @param generator    supplier of a genome supplier for generating new individuals
	 * @param totalCycles  the total number of optimization cycles to run
	 */
	public AudioSceneOptimizer(AudioScene<?> scene,
							   Supplier<GenomeBreeder<PackedCollection>> breeder,
							   Supplier<Supplier<Genome<PackedCollection>>> generator,
							   int totalCycles) {
		super(scene.getChannelCount() + 1, null, breeder, generator, POPULATION_FILE, totalCycles);
		setChildrenFunction(
				children -> {
					boolean initPopulation = false;

					if (population == null) {
						population = new AudioScenePopulation(scene, new ArrayList<>());
						initPopulation = true;
					}

					int expectedCount = children.isEmpty() ?
							PopulationOptimizer.popSize : children.size();
					List<Genome<PackedCollection>> genomes = new ArrayList<>();
					IntStream.range(0, expectedCount)
							.mapToObj(i -> i < children.size() ? children.get(i) : null)
							.map(g -> population.validateGenome(g) ? g : null)
							.map(g -> g == null ? getGenerator().get() : g)
							.forEach(genomes::add);
					population.setGenomes(genomes);

					if (initPopulation) {
						AudioHealthComputation hc = (AudioHealthComputation) getHealthComputation();
						hc.setWaveDetailsProcessor(detailsProcessor);

						if (enableVerbose) log("Initializing AudioScenePopulation");
						population.init(population.getGenomes().get(0), hc.getOutput(),
								null, ((StableDurationHealthComputation) hc).getBatchSize());

						if (enableVerbose) {
							log("AudioScenePopulation initialized (getCells duration = " +
									AudioScene.console.timing("getCells").getTotal() + ")");
						}
					}

					resetGenerator();

					return population;
				});
	}

	/**
	 * Sets an optional processor that receives {@link WaveDetails} metadata produced
	 * during each health evaluation cycle.
	 *
	 * @param processor the wave details consumer, or {@code null} to disable
	 */
	public void setWaveDetailsProcessor(Consumer<WaveDetails> processor) {
		this.detailsProcessor = processor;
	}

	@Override
	public void destroy() {
		super.destroy();
		if (population != null) population.destroy();
		population = null;
	}

	/**
	 * Creates an {@code AudioSceneOptimizer} using the default random genome generator
	 * derived from the scene's genome.
	 *
	 * @param scene  the audio scene to optimize
	 * @param cycles the number of optimization cycles to run
	 * @return a configured {@code AudioSceneOptimizer}
	 */
	public static AudioSceneOptimizer build(AudioScene<?> scene, int cycles) {
		return build(() -> scene.getGenome()::random, scene, cycles);
	}

	/**
	 * Creates an {@code AudioSceneOptimizer} with an explicit genome generator supplier.
	 *
	 * @param generator supplier of a genome supplier for generating new individuals
	 * @param scene     the audio scene to optimize
	 * @param cycles    the number of optimization cycles to run
	 * @return a configured {@code AudioSceneOptimizer}
	 */
	public static AudioSceneOptimizer build(Supplier<Supplier<Genome<PackedCollection>>> generator,
											AudioScene<?> scene, int cycles) {
		return new AudioSceneOptimizer(scene, () -> defaultBreeder(breederPerturbation), generator, cycles);
	}

	/**
	 * Creates the default genome breeder that combines two parent genomes via perturbation.
	 * Each gene value is perturbed by a random scale factor derived from {@code magnitude}.
	 *
	 * @param magnitude the base perturbation magnitude applied during breeding
	 * @return a {@link GenomeBreeder} that produces perturbed offspring genomes
	 */
	public static GenomeBreeder<PackedCollection> defaultBreeder(double magnitude) {
		return (g1, g2) -> {
			PackedCollection a = ((ProjectedGenome) g1).getParameters();
			PackedCollection b = ((ProjectedGenome) g2).getParameters();

			int len = a.getShape().getTotalSize();
			PackedCollection combined = new PackedCollection(len);

			double scale = (1 + Math.random()) * magnitude / 2;
			for (int i = 0; i < len; i++) {
				combined.setMem(i, Breeders.perturbation(a.toDouble(i), b.toDouble(i), scale));
			}

			return new ProjectedGenome(new PackedCollection(combined));
		};
	}

	/**
	 * Configures the active feature set for the audio scene based on a numeric feature level.
	 * Higher levels enable more processing stages such as EFX, reverb, and automation.
	 *
	 * @param featureLevel the desired feature level (0 = minimal, 7 = full feature set)
	 */
	public static void setFeatureLevel(int featureLevel) {
		PatternElementFactory.enableVolumeEnvelope = featureLevel > 0;
		PatternElementFactory.enableFilterEnvelope = featureLevel > 0;

		MixdownManager.disableClean = false;
		MixdownManager.enableSourcesOnly = featureLevel < 0;

		EfxManager.enableEfx = featureLevel > 1;
		MixdownManager.enableEfx = featureLevel > 2;
		MixdownManager.enableEfxFilters = featureLevel > 2;
		MixdownManager.enableTransmission = featureLevel > 3;
		MixdownManager.enableMainFilterUp = featureLevel > 4;
		MixdownManager.enableAutomationManager = featureLevel > 4;
		MixdownManager.enableWetInAdjustment = featureLevel > 5;
		MixdownManager.enableMasterFilterDown = featureLevel > 5;
		MixdownManager.enableReverb = featureLevel > 6;

		StableDurationHealthComputation.enableTimeout = false;
		SilenceDurationHealthComputation.enableSilenceCheck = false;
		enableStemOutput = true;

		if (enableShort) {
			HealthComputationAdapter.setStandardDuration(16);
		}
	}

	/**
	 * Configures logging verbosity and optional hardware-level profiling across all optimizer
	 * subsystems. Higher verbosity values enable progressively more detailed logging.
	 *
	 * @param verbosity     the verbosity level (negative disables breeding; positive enables subsystem logging)
	 * @param enableProfile when {@code true} a hardware operation profile node is created and assigned
	 * @return an {@link OperationProfileNode} if profiling is enabled, otherwise {@code null}
	 */
	public static OperationProfileNode setVerbosity(int verbosity, boolean enableProfile) {
		// Verbosity level -1
		enableBreeding = verbosity < 0;

		// Verbosity level 1;
		NoteAudioProvider.enableVerbose = verbosity > 0;

		// Verbosity level 2
		AudioSceneOptimizer.enableVerbose = verbosity > 1;
		PopulationOptimizer.enableVerbose = verbosity > 1;
		SilenceDurationHealthComputation.enableVerbose = verbosity > 1;
		StableDurationHealthComputation.enableProfileAutosave = verbosity > 1;

		// Verbosity level 3
		PatternSystemManager.enableVerbose = verbosity > 2;
		enableDisplayGenomes = verbosity > 2;
		Hardware.enableVerbose = verbosity > 2;
		HardwareOperator.enableLog = verbosity > 2;

		// Verbosity level 4
		WaveOutput.enableVerbose = verbosity > 3;
		NativeComputeContext.enableVerbose = verbosity > 3;

		// Verbosity level 5
		HardwareOperator.enableVerboseLog = verbosity > 4;

		OperationProfileNode profile = enableProfile ? new OperationProfileNode("AudioSceneOptimizer") : null;
		Hardware.getLocalHardware().assignProfile(profile);
		StableDurationHealthComputation.profile = profile;
		return profile;
	}

	/**
	 * Build a {@link AudioSceneOptimizer} and initialize and run it.
	 *
	 * @see  AudioSceneOptimizer#build(AudioScene, int)
	 * @see  AudioSceneOptimizer#init
	 * @see  AudioSceneOptimizer#run()
	 */
	public static void main(String[] args) throws IOException {
		// Configure logging and profiling
		Console.root().addListener(OutputFeatures.fileOutput("results/logs/audio-scene.out"));
		OperationProfileNode profile = setVerbosity(verbosity, enableProfile);

		// Setup features
		PopulationOptimizer.popSize = enableBreeding ? 10 : 3;
		setFeatureLevel(7);

		// Create computations before applying Heap
		AudioProcessingUtils.init();
		WaveData.init();

		try {
			if (enableHeap) {
				Heap heap = new Heap(DEFAULT_HEAP_SIZE);
				heap.use(() -> run(profile));
			} else {
				run(profile);
			}
		} finally {
			File results = new File("results");
			if (!results.exists()) results.mkdir();

			if (profile != null) {
				profile.save("results/optimizer.xml");
			}

			Hardware.getLocalHardware().getAllDataContexts().forEach(DataContext::destroy);
		}
	}

	/**
	 * Creates a new scene, builds and initializes an optimizer, then executes the optimization
	 * loop. Prints the profile and additional metrics summaries after the run completes.
	 *
	 * @param profile the operation profile node for collecting timing data, or {@code null}
	 */
	public static void run(OperationProfileNode profile) {
		try {
			AudioScene<?> scene = createScene();
			AudioSceneOptimizer opt = build(scene, enableBreeding ? 5 : 1);
			opt.init();
			opt.run();
		} finally {
			if (profile != null)
				profile.print();

			if (enableVerbose)
				PatternLayerManager.sizes.print();

			if (AudioSumProvider.timing.getTotal() > 120)
				AudioSumProvider.timing.print();

			if (MemoryDataReplacementMap.profile != null &&
					MemoryDataReplacementMap.profile.getMetric().getTotal() > 10)
				MemoryDataReplacementMap.profile.print();
		}
	}

	/**
	 * Creates and configures a default {@link AudioScene} using standard constants and settings.
	 * Loads pattern factory data from the local destination and configures keyboard tuning and
	 * library root.
	 *
	 * @return a fully configured {@link AudioScene} ready for optimization
	 */
	public static AudioScene<?> createScene() {
		double bpm = 120.0;
		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		int delayLayers = AudioScene.DEFAULT_DELAY_LAYERS;

		AudioScene<?> scene = new AudioScene<>(bpm, sourceCount, delayLayers, OutputLine.sampleRate);
		loadChoices(scene);

		scene.setTuning(new DefaultKeyboardTuning());
		scene.setLibraryRoot(new FileWaveDataProviderNode(new File(LIBRARY)));

		AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.defaultSettings(sourceCount,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION);

		if (singleChannel >= 0) {
			PatternSystemManager.enableWarnings = false;

			settings.getPatternSystem().setPatterns(
					settings
							.getPatternSystem()
							.getPatterns()
							.stream()
							.filter(p -> p.getChannel() == singleChannel)
							.collect(Collectors.toList()));
		}

		scene.setSettings(settings);

		if (enableShort) {
			scene.setTotalMeasures(8);
			scene.setPatternActivityBias(1.0);
		}

		return scene;
	}

	/**
	 * Loads pattern factory JSON data into the specified scene, throwing a runtime exception
	 * if the file cannot be read.
	 *
	 * @param scene the audio scene to populate with pattern choices
	 */
	private static void loadChoices(AudioScene scene) {
		try {
			scene.loadPatterns(SystemUtils.getLocalDestination("pattern-factory.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
