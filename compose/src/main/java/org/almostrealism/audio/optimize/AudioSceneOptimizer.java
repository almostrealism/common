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

package org.almostrealism.audio.optimize;

import io.almostrealism.code.DataContext;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.arrange.EfxManager;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.filter.AudioSumProvider;
import org.almostrealism.audio.health.AudioHealthComputation;
import org.almostrealism.audio.health.HealthComputationAdapter;
import org.almostrealism.audio.health.SilenceDurationHealthComputation;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.pattern.PatternElementFactory;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.pattern.PatternSystemManager;
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
import org.almostrealism.io.ConsoleFeatures;
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

public class AudioSceneOptimizer extends AudioPopulationOptimizer<TemporalCellular> {
	public static final String POPULATION_FILE = SystemUtils.getLocalDestination("population.json");

	public static final int verbosity = SystemUtils.getInt("AR_AUDIO_OPT_VERBOSITY").orElse(0);
	public static final int singleChannel = -1;

	public static boolean enableVerbose = false;
	public static boolean enableProfile = true;
	public static boolean enableHeap = false;
	public static boolean enableShort = false;

	public static int DEFAULT_HEAP_SIZE = 384 * 1024 * 1024;
	public static double breederPerturbation = 0.02;

	public static String LIBRARY = "Library";

	private static final ConsoleFeatures console = Console.root().features(AudioSceneOptimizer.class);

	static {
		String env = System.getenv("AR_RINGS_LIBRARY");
		if (env != null) LIBRARY = env;

		String arg = System.getProperty("AR_RINGS_LIBRARY");
		if (arg != null) LIBRARY = arg;
	}

	private AudioScenePopulation population;
	private Consumer<WaveDetails> detailsProcessor;

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
						population.init(population.getGenomes().get(0), hc.getOutput());

						if (enableVerbose) {
							log("AudioScenePopulation initialized (getCells duration = " +
									AudioScene.console.timing("getCells").getTotal() + ")");
						}
					}

					resetGenerator();

					return population;
				});
	}

	public void setWaveDetailsProcessor(Consumer<WaveDetails> processor) {
		this.detailsProcessor = processor;
	}

	@Override
	public void destroy() {
		super.destroy();
		if (population != null) population.destroy();
		population = null;
	}

	public static AudioSceneOptimizer build(AudioScene<?> scene, int cycles) {
		return build(() -> scene.getGenome()::random, scene, cycles);
	}

	public static AudioSceneOptimizer build(Supplier<Supplier<Genome<PackedCollection>>> generator,
											AudioScene<?> scene, int cycles) {
		return new AudioSceneOptimizer(scene, () -> defaultBreeder(breederPerturbation), generator, cycles);
	}

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

			profile.save("results/optimizer.xml");

			Hardware.getLocalHardware().getAllDataContexts().forEach(DataContext::destroy);
		}
	}

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

	public static AudioScene<?> createScene() {
		double bpm = 120.0;
		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		int delayLayers = AudioScene.DEFAULT_DELAY_LAYERS;

		AudioScene<?> scene = new AudioScene<>(bpm, sourceCount, delayLayers, OutputLine.sampleRate);
		loadChoices(scene);

		scene.setTuning(new DefaultKeyboardTuning());
		scene.setLibraryRoot(new FileWaveDataProviderNode(new File(LIBRARY)));

		AudioScene.Settings settings = AudioScene.Settings.defaultSettings(sourceCount,
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

	private static void loadChoices(AudioScene scene) {
		try {
			scene.loadPatterns(SystemUtils.getLocalDestination("pattern-factory.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
