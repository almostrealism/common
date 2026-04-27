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

package org.almostrealism.studio;
import org.almostrealism.audio.Cells;
import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.CellFeatures;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.almostrealism.lifecycle.Setup;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.arrange.RiseManager;
import org.almostrealism.studio.arrange.SceneSectionManager;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.studio.generative.GenerationManager;
import org.almostrealism.studio.generative.GenerationProvider;
import org.almostrealism.studio.generative.NoOpGenerationProvider;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.persistence.MigrationClassLoader;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.audio.CellList;
import org.almostrealism.music.pattern.ChordProgressionManager;
import org.almostrealism.music.pattern.PatternAudioBuffer;
import org.almostrealism.music.pattern.NoteAudioChoiceList;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.music.pattern.RenderedNoteAudio;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.Console;
import org.almostrealism.io.TimingMetric;
import org.almostrealism.space.Animation;
import org.almostrealism.time.Frequency;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Central orchestrator for audio scene composition, arrangement, and generation.
 *
 * <p>{@code AudioScene} is the primary entry point for constructing and rendering
 * complex audio compositions in the Almost Realism framework. It coordinates multiple
 * subsystems including pattern management, effects processing, automation, and
 * time synchronization.</p>
 *
 * <h2>Architecture Overview</h2>
 *
 * <p>An AudioScene manages several interconnected managers:</p>
 * <ul>
 *   <li>{@link PatternSystemManager} - Musical pattern organization and rendering</li>
 *   <li>{@link MixdownManager} - Effects routing, delays, and reverb</li>
 *   <li>{@link AutomationManager} - Parameter automation over time</li>
 *   <li>{@link GlobalTimeManager} - Playback position and reset points</li>
 *   <li>{@link SceneSectionManager} - Musical section structure</li>
 *   <li>{@link EfxManager} - Per-channel effects</li>
 *   <li>{@link RiseManager} - Rise/swell effect processing</li>
 * </ul>
 *
 * <h2>Execution Model</h2>
 *
 * <p>AudioScene follows a two-phase execution model:</p>
 *
 * <h3>Setup Phase</h3>
 * <p>Runs once before audio processing begins. Currently includes:</p>
 * <ul>
 *   <li>Pattern rendering via {@link PatternSystemManager#sum}</li>
 *   <li>Buffer allocation for pattern destinations</li>
 *   <li>Automation initialization</li>
 *   <li>Effects chain compilation</li>
 * </ul>
 *
 * <h3>Tick Phase</h3>
 * <p>Runs repeatedly for each audio buffer:</p>
 * <ul>
 *   <li>Effects processing via {@link CellList}</li>
 *   <li>Output writing</li>
 *   <li>Time advancement</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create scene at 120 BPM, 44100 Hz sample rate
 * AudioScene<?> scene = new AudioScene<>(120.0, 6, 3, 44100);
 *
 * // Configure the scene
 * scene.loadSettings(new File("scene.json"));
 * scene.loadPatterns("patterns.json");
 * scene.setLibraryRoot(new FileWaveDataProviderNode(new File("samples/")));
 *
 * // Build the realtime runner and drive it one buffer at a time
 * TemporalCellular runner = scene.runnerRealTime(output, 1024);
 * runner.setup().get().run();
 * Runnable tick = runner.tick().get();
 * for (int i = 0; i < bufferCount; i++) tick.run();
 * }</pre>
 *
 * <h2>Pattern Rendering Flow</h2>
 *
 * <p>Pattern rendering is handled by {@link PatternAudioBuffer}, which calls
 * {@link PatternSystemManager#sum} to render patterns for a channel.
 * {@link #getPatternChannel} constructs the per-channel cell pipeline used by
 * {@link #runnerRealTime}.</p>
 *
 * <h2>Genetic Algorithm Integration</h2>
 *
 * <p>AudioScene integrates with the heredity module for evolutionary optimization.
 * The {@link #genome} field contains chromosomes for various parameters including
 * patterns, automation, effects, and section structure.</p>
 *
 * @param <T> The type of visual scene element, typically extending {@link ShadableSurface}
 *
 * @see PatternSystemManager
 * @see MixdownManager
 * @see CellList
 *
 * @author Michael Murray
 */
public class AudioScene<T extends ShadableSurface> implements Setup, Destroyable, CellFeatures {
	/** Console logger scoped to {@code AudioScene} for timing and debug output. */
	public static final Console console = CellFeatures.console.child();

	/** Timing metric that tracks the cumulative duration of {@code getCells} calls. */
	private static final TimingMetric getCellsTime = console.timing("getCells");

	/** Registry of all currently active {@code AudioScene} instances. */
	private static final List<AudioScene<?>> activeInstances = new ArrayList<>();

	/** Default number of audio source channels per scene. */
	public static final int DEFAULT_SOURCE_COUNT = 6;

	/** Default PCM buffer size in frames for real-time rendering. */
	public static final int DEFAULT_REALTIME_BUFFER_SIZE = 1024;

	/** Default number of delay echo layers per scene. */
	public static final int DEFAULT_DELAY_LAYERS = 3;

	/** Default number of patterns available per channel. */
	public static final int DEFAULT_PATTERNS_PER_CHANNEL = 6;

	/** Maximum number of scene sections supported by the genome structure. */
	public static final int MAX_SCENE_SECTIONS = 16;

	/** Default operator returning the number of active patterns for each channel index. */
	public static final IntUnaryOperator DEFAULT_ACTIVE_PATTERNS;

	/** Default operator returning the number of pattern layers for each channel index. */
	public static final IntUnaryOperator DEFAULT_LAYERS;

	/** Default function returning the minimum layer scale for each channel index. */
	public static final IntToDoubleFunction DEFAULT_LAYER_SCALE;

	/** Default operator returning the pattern loop duration (in measures) for each channel index. */
	public static final IntUnaryOperator DEFAULT_DURATION;

	/** Probability that a random genome variation will be applied to any given gene. */
	public static double variationRate = 0.1;

	/** Gaussian standard deviation multiplier for random genome variation perturbations. */
	public static double variationIntensity = 0.01;

	static {
		DEFAULT_ACTIVE_PATTERNS = c ->
				switch (c) {
					case 0 -> 4; // 5;
					case 1 -> 4; // 5;
					case 2 -> 1;
					case 3 -> 1;
					case 4 -> 1;
					case 5 -> 2;
					default -> throw new IllegalArgumentException("Unexpected value: " + c);
				};

//		DEFAULT_LAYERS = c ->
//				switch (c) {
//					case 0 -> 4; // 5;
//					case 1 -> 3; // 5;
//					case 2 -> 3; // 6;
//					case 3 -> 3; // 6;
//					case 4 -> 4; // 5;
//					case 5 -> 1;
//					default -> throw new IllegalArgumentException("Unexpected value: " + c);
//				};

		DEFAULT_LAYERS = c -> 6;

		DEFAULT_LAYER_SCALE = c ->
				switch (c) {
					case 0 -> 0.25;
					case 1 -> 0.0625;
					case 2 -> 0.0625;
					case 3 -> 0.0625;
					case 4 -> 0.0625;
					case 5 -> 0.125;
					default -> 0.0625;
				};

		DEFAULT_DURATION = c ->
				switch (c) {
					case 0 -> 1;
					case 1 -> 4;
					case 2 -> 8; // 16;
					case 3 -> 8; // 16;
					case 4 -> 8;
					case 5 -> 16;
					default -> (int) Math.pow(2.0, c - 1);
				};
	}

	/** Audio sample rate in Hz for this scene. */
	private final int sampleRate;

	/** Tempo in beats per minute. */
	private double bpm;

	/** Number of mix channels in this scene. */
	private final int channelCount;

	/** Number of delay echo layers in the mixdown pipeline. */
	private final int delayLayerCount;

	/** Number of beats per measure (time signature numerator). */
	private int measureSize = 4;

	/** Total number of measures for the full composition. */
	private int totalMeasures = 1;

	/** Optional visual scene backing this audio scene; may be {@code null}. */
	private final Animation<T> scene;

	/** Manages global clock resets and measure-boundary timing. */
	private final GlobalTimeManager time;

	/** Keyboard tuning used to resolve note frequencies. */
	private KeyboardTuning tuning;

	/** Manages chord progression for the composition. */
	private final ChordProgressionManager progression;

	/** Audio library providing sample data for patterns; may be {@code null}. */
	private AudioLibrary library;

	/** Manages pattern layers and note scheduling for all channels. */
	private final PatternSystemManager patterns;

	/** Human-readable names for each mix channel. */
	private final List<String> channelNames;

	/** Bias factor applied to pattern activity probability during rendering. */
	private double patternActivityBias;

	/** Manages scene sections that define structural regions of the composition. */
	private final SceneSectionManager sections;

	/** Manages parameter automation envelopes over time. */
	private final AutomationManager automation;

	/** Manages per-channel effects chains. */
	private final EfxManager efx;

	/** Manages rise/swell effect processing. */
	private final RiseManager riser;

	/** Manages delays, reverb, and final mixdown processing. */
	private final MixdownManager mixdown;

	/** Manages ML-based audio generation integration. */
	private final GenerationManager generation;

	/** Genome encoding all evolvable parameters for this scene. */
	private final ProjectedGenome genome;

	/** One-shot setup operation compiled during the first {@link #getCells} call. */
	private OperationList setup;

	/** Pattern audio buffers created for each channel during setup. */
	private List<PatternAudioBuffer> renderCells = new ArrayList<>();

	/** Consolidated backing buffer for all render cell outputs; allocated during {@code getCells}. */
	private PackedCollection consolidatedRenderBuffer;

	/** Index into the consolidated render buffer for the next allocation. */
	private int renderBufferIndex;

	/** The active cell list produced by the most recent {@code getCells} call. */
	private CellList activeCells;

	/** Cached automation level function built lazily from the automation manager. */
	private Function<PackedCollection, Factor<PackedCollection>> automationLevel;

	/** Listeners notified whenever the tempo changes. */
	private final List<Consumer<Frequency>> tempoListeners;

	/** Listeners notified whenever the total composition duration changes. */
	private final List<DoubleConsumer> durationListeners;

	/**
	 * Creates an audio scene backed by the given visual scene at the specified tempo,
	 * using default source count and delay layer count.
	 *
	 * @param scene      the optional visual scene; may be {@code null}
	 * @param bpm        the tempo in beats per minute
	 * @param sampleRate the audio sample rate in Hz
	 */
	public AudioScene(Animation<T> scene, double bpm, int sampleRate) {
		this(scene, bpm, DEFAULT_SOURCE_COUNT, DEFAULT_DELAY_LAYERS, sampleRate);
	}

	/**
	 * Creates an audio scene without a visual scene at the specified tempo, channel count,
	 * delay layers, and sample rate.
	 *
	 * @param bpm         the tempo in beats per minute
	 * @param channels    the number of mix channels
	 * @param delayLayers the number of delay echo layers in the mixdown pipeline
	 * @param sampleRate  the audio sample rate in Hz
	 */
	public AudioScene(double bpm, int channels, int delayLayers,
					  int sampleRate) {
		this(null, bpm, channels, delayLayers, sampleRate);
	}

	/**
	 * Creates an audio scene backed by the given visual scene at the specified tempo,
	 * channel count, delay layers, and sample rate. Uses an empty note audio choices list
	 * and a no-op generation provider.
	 *
	 * @param scene       the optional visual scene; may be {@code null}
	 * @param bpm         the tempo in beats per minute
	 * @param channels    the number of mix channels
	 * @param delayLayers the number of delay echo layers in the mixdown pipeline
	 * @param sampleRate  the audio sample rate in Hz
	 */
	public AudioScene(Animation<T> scene, double bpm, int channels, int delayLayers,
					  int sampleRate) {
		this(scene, bpm, channels, delayLayers, sampleRate, new ArrayList<>(), new NoOpGenerationProvider());
	}

	/**
	 * Primary constructor that fully initializes the audio scene with all manager subsystems.
	 *
	 * @param scene      the optional visual scene; may be {@code null}
	 * @param bpm        the initial tempo in beats per minute
	 * @param channels   the number of mix channels
	 * @param delayLayers the number of delay echo layers in the mixdown pipeline
	 * @param sampleRate the audio sample rate in Hz
	 * @param choices    the initial list of note audio choices for pattern rendering
	 * @param generation the generation provider for ML-based audio generation
	 */
	public AudioScene(Animation<T> scene, double bpm, int channels, int delayLayers,
					  int sampleRate, List<NoteAudioChoice> choices,
					  GenerationProvider generation) {
		this.sampleRate = sampleRate;
		this.bpm = bpm;
		this.channelCount = channels;
		this.delayLayerCount = delayLayers;
		this.scene = scene;

		this.tempoListeners = new ArrayList<>();
		this.durationListeners = new ArrayList<>();

		this.time = new GlobalTimeManager(measure -> (int) (measure * getMeasureDuration() * getSampleRate()));

		this.genome = new ProjectedGenome(16);

		List<ProjectedChromosome> sectionChromosomes =
				IntStream.range(0, MAX_SCENE_SECTIONS)
					.mapToObj(i -> genome.addChromosome())
					.toList();

		this.tuning = new DefaultKeyboardTuning();
		this.sections = new SceneSectionManager(sectionChromosomes, channels, this::getTempo, this::getMeasureDuration, getSampleRate());
		this.progression = new ChordProgressionManager(genome.addChromosome(),
								WesternScales.minor(WesternChromatic.G1, 1));
		this.progression.setSize(16);
		this.progression.setDuration(8);

		List<ProjectedChromosome> patternChromosomes =
				IntStream.range(0, channels * DEFAULT_PATTERNS_PER_CHANNEL)
					.mapToObj(i -> genome.addChromosome())
					.toList();

		patterns = new PatternSystemManager(choices, patternChromosomes);
		patterns.init();

		this.channelNames = new ArrayList<>();

		this.automation = new AutomationManager(genome.addChromosome(), time.getClock(),
											this::getMeasureDuration, getSampleRate());
		this.efx = new EfxManager(genome.addChromosome(), channels,
									automation, this::getBeatDuration, getSampleRate());
		this.riser = new RiseManager(genome.addChromosome(),
				() -> getContext(new ChannelInfo(0, ChannelInfo.Type.RISE, null)), getSampleRate());
		this.mixdown = new MixdownManager(genome.addChromosome(),
									channels, delayLayers,
									automation, time.getClock(), getSampleRate());

		// Consolidate gene values so all genes within each chromosome share a
		// single contiguous PackedCollection.  This reduces the number of kernel
		// arguments when the Loop scope is compiled for both offline and real-time.
		genome.consolidateGeneValues();

		this.generation = new GenerationManager(patterns, generation);
		activeInstances.add(this);
	}

	/**
	 * Sets the scene tempo in beats per minute.
	 *
	 * @param bpm the tempo in beats per minute
	 * @deprecated Use {@link #setTempo(Frequency)} instead.
	 */
	@Deprecated
	public void setBPM(double bpm) {
		this.bpm = bpm;
		tempoListeners.forEach(l -> l.accept(Frequency.forBPM(bpm)));
		triggerDurationChange();
	}

	@Deprecated
	public double getBPM() { return this.bpm; }

	/**
	 * Sets the scene tempo and notifies all registered tempo listeners.
	 *
	 * @param tempo the new tempo as a {@link Frequency}
	 */
	public void setTempo(Frequency tempo) {
		this.bpm = tempo.asBPM();
		tempoListeners.forEach(l -> l.accept(tempo));
		triggerDurationChange();
	}

	/**
	 * Returns the current tempo as a {@link Frequency}.
	 *
	 * @return the current tempo
	 */
	public Frequency getTempo() { return Frequency.forBPM(bpm); }

	/**
	 * Sets the keyboard tuning used to resolve note frequencies and propagates it
	 * to the pattern system manager.
	 *
	 * @param tuning the keyboard tuning to apply
	 */
	public void setTuning(KeyboardTuning tuning) {
		this.tuning = tuning;
		patterns.setTuning(tuning);
	}

	/**
	 * Returns the current keyboard tuning.
	 *
	 * @return the keyboard tuning
	 */
	public KeyboardTuning getTuning() { return tuning; }

	/**
	 * Returns the audio library providing sample data for pattern rendering.
	 *
	 * @return the audio library, or {@code null} if none has been set
	 */
	public AudioLibrary getLibrary() { return library; }

	/**
	 * Sets the audio library and refreshes the pattern system with the library's sample tree.
	 *
	 * @param library the new audio library
	 */
	public void setLibrary(AudioLibrary library) {
		setLibrary(library, null);
	}

	/**
	 * Sets the audio library with optional progress reporting during sample tree loading.
	 *
	 * @param library  the new audio library
	 * @param progress optional consumer that receives loading progress values in {@code [0, 1]};
	 *                 may be {@code null}
	 */
	public void setLibrary(AudioLibrary library, DoubleConsumer progress) {
		this.library = library;

		if (getLibrary() != null) {
			patterns.setTree(getLibrary().getRoot(), progress);
		}
	}

	/**
	 * Convenience method that creates an {@link AudioLibrary} from the given provider tree
	 * and sets it as the scene's library.
	 *
	 * @param tree the file-based wave data provider tree
	 */
	public void setLibraryRoot(FileWaveDataProviderTree tree) {
		setLibraryRoot(tree, null);
	}

	/**
	 * Convenience method that creates an {@link AudioLibrary} from the given provider tree
	 * and sets it as the scene's library, with optional progress reporting.
	 *
	 * @param tree     the file-based wave data provider tree
	 * @param progress optional consumer that receives loading progress values; may be {@code null}
	 */
	public void setLibraryRoot(FileWaveDataProviderTree tree, DoubleConsumer progress) {
		setLibrary(new AudioLibrary(tree, getSampleRate()), progress);
	}

	/**
	 * Loads note audio choices from the specified JSON patterns file and adds them to the
	 * pattern manager's choices list.
	 *
	 * @param patternsFile path to the JSON patterns file
	 * @throws IOException if the file cannot be read
	 */
	public void loadPatterns(String patternsFile) throws IOException {
		try (FileInputStream in = new FileInputStream(patternsFile)) {
			NoteAudioChoiceList choices = AudioSceneLoader.defaultMapper()
					.readValue(MigrationClassLoader.migrateStream(in),
							NoteAudioChoiceList.class);
			getPatternManager().getChoices().addAll(choices);
		}
	}

	/**
	 * Returns the optional visual scene backing this audio scene.
	 *
	 * @return the visual scene, or {@code null} if none was provided
	 */
	public Animation<T> getScene() { return scene; }

	/**
	 * Returns a copy of the scene's current genome parameters.
	 *
	 * @return a new {@link ProjectedGenome} with the current parameter values
	 */
	public ProjectedGenome getGenome() { return new ProjectedGenome(genome.getParameters()); }

	/**
	 * Assigns new genome parameters and refreshes all derived values.
	 *
	 * <p><b>Runner reuse:</b> The cell graph built by {@link #runnerRealTime} is
	 * structurally independent of the genome. All genome-derived values flow through
	 * {@link PackedCollection} references (via {@code cp()}) that are updated in-place
	 * by this method. A runner that was already compiled can be reused after calling
	 * this method &mdash; the compiled kernel automatically reads the new values on
	 * its next tick without requiring recompilation.</p>
	 *
	 * <p>The pattern preparation phase ({@link PatternAudioBuffer#prepareBatch()})
	 * runs outside the compiled loop in Java, so it naturally uses the refreshed
	 * parameter state.</p>
	 *
	 * @param genome the new genome whose parameters will be assigned
	 */
	public void assignGenome(ProjectedGenome genome) {
		this.genome.assignTo(genome.getParameters());
		this.progression.refreshParameters();
		this.patterns.refreshParameters();
	}

	/**
	 * Adds a scene section starting at the given measure position with the specified length.
	 *
	 * @param position the starting measure index of the section
	 * @param length   the duration of the section in measures
	 */
	public void addSection(int position, int length) {
		sections.addSection(position, length);
	}

	/**
	 * Adds a global time reset (break) at the specified measure.
	 *
	 * @param measure the measure index at which to insert a reset
	 */
	public void addBreak(int measure) { time.addReset(measure); }

	/**
	 * Returns the global time manager that controls clock resets and measure boundaries.
	 *
	 * @return the global time manager
	 */
	public GlobalTimeManager getTimeManager() { return time; }

	/**
	 * Returns the scene section manager that organizes structural sections of the composition.
	 *
	 * @return the scene section manager
	 */
	public SceneSectionManager getSectionManager() { return sections; }

	/**
	 * Returns the chord progression manager for this scene.
	 *
	 * @return the chord progression manager
	 */
	public ChordProgressionManager getChordProgression() { return progression; }

	/**
	 * Returns the pattern system manager that handles note scheduling and layer rendering.
	 *
	 * @return the pattern system manager
	 */
	public PatternSystemManager getPatternManager() { return patterns; }

	/**
	 * Returns the automation manager that handles parameter envelope automation.
	 *
	 * @return the automation manager
	 */
	public AutomationManager getAutomationManager() { return automation; }

	/**
	 * Returns the per-channel effects manager.
	 *
	 * @return the EFX manager
	 */
	public EfxManager getEfxManager() { return efx; }

	/**
	 * Returns the consolidated render buffer that backs all {@link PatternAudioBuffer}
	 * output buffers, or {@code null} if {@link #getCells} has not been called.
	 *
	 * <p>Exposed for testing to verify that output buffer consolidation is active.</p>
	 */
	public PackedCollection getConsolidatedRenderBuffer() { return consolidatedRenderBuffer; }
	/**
	 * Returns the mixdown manager that handles delay, reverb, and final mix bus processing.
	 *
	 * @return the mixdown manager
	 */
	public MixdownManager getMixdownManager() { return mixdown; }

	/**
	 * Returns the generation manager for ML-based audio generation integration.
	 *
	 * @return the generation manager
	 */
	public GenerationManager getGenerationManager() { return generation; }

	/**
	 * Registers a listener that is notified whenever the tempo changes.
	 *
	 * @param listener the listener to add
	 */
	public void addTempoListener(Consumer<Frequency> listener) { this.tempoListeners.add(listener); }

	/**
	 * Removes a previously registered tempo listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeTempoListener(Consumer<Frequency> listener) { this.tempoListeners.remove(listener); }

	/**
	 * Registers a listener that receives the total composition duration (in seconds) whenever
	 * tempo, measure size, or total measure count changes.
	 *
	 * @param listener the listener to add
	 */
	public void addDurationListener(DoubleConsumer listener) { this.durationListeners.add(listener); }

	/**
	 * Removes a previously registered duration listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeDurationListener(DoubleConsumer listener) { this.durationListeners.remove(listener); }

	/**
	 * Returns the number of mix channels in this scene.
	 *
	 * @return the channel count
	 */
	public int getChannelCount() { return channelCount; }

	/**
	 * Returns the number of delay echo layers in the mixdown pipeline.
	 *
	 * @return the delay layer count
	 */
	public int getDelayLayerCount() { return delayLayerCount; }

	/**
	 * Returns the pattern activity bias applied to pattern scheduling probability.
	 *
	 * @return the pattern activity bias
	 */
	public double getPatternActivityBias() { return patternActivityBias; }

	/**
	 * Sets the pattern activity bias applied to pattern scheduling probability.
	 *
	 * @param patternActivityBias the new bias value
	 */
	public void setPatternActivityBias(double patternActivityBias) { this.patternActivityBias = patternActivityBias; }

	/**
	 * Returns the human-readable channel names list.
	 *
	 * @return the channel names
	 */
	public List<String> getChannelNames() { return channelNames; }

	/**
	 * Returns the duration of a single beat in seconds at the current tempo.
	 *
	 * @return beat duration in seconds
	 */
	public double getBeatDuration() { return getTempo().l(1); }

	/**
	 * Sets the number of beats per measure and notifies duration listeners.
	 *
	 * @param measureSize the new beats-per-measure value
	 */
	public void setMeasureSize(int measureSize) { this.measureSize = measureSize; triggerDurationChange(); }

	/**
	 * Returns the number of beats per measure.
	 *
	 * @return the measure size
	 */
	public int getMeasureSize() { return measureSize; }

	/**
	 * Returns the duration of a single measure in seconds at the current tempo.
	 *
	 * @return measure duration in seconds
	 */
	public double getMeasureDuration() { return getTempo().l(getMeasureSize()); }

	/**
	 * Returns the number of audio samples in a single measure at the configured sample rate.
	 *
	 * @return samples per measure
	 */
	public int getMeasureSamples() { return (int) (getMeasureDuration() * getSampleRate()); }

	/**
	 * Sets the total number of measures in the composition and notifies duration listeners.
	 *
	 * @param measures the new total measure count
	 */
	public void setTotalMeasures(int measures) { this.totalMeasures = measures; triggerDurationChange(); }

	/**
	 * Returns the total number of measures in the composition.
	 *
	 * @return total measure count
	 */
	public int getTotalMeasures() { return totalMeasures; }

	/**
	 * Returns the total number of beats in the composition.
	 *
	 * @return total beat count
	 */
	public int getTotalBeats() { return totalMeasures * measureSize; }

	/**
	 * Returns the total duration of the composition in seconds.
	 *
	 * @return total duration in seconds
	 */
	public double getTotalDuration() { return getTempo().l(getTotalBeats()); }

	/**
	 * Returns the total number of samples in the full composition at the configured sample rate.
	 *
	 * @return total sample count
	 */
	public int getTotalSamples() { return (int) (getTotalDuration() * getSampleRate()); }

	/**
	 * Returns the audio sample rate in Hz.
	 *
	 * @return sample rate in Hz
	 */
	public int getSampleRate() { return sampleRate; }

	/**
	 * Returns an {@link AudioSceneContext} with no specific channel selected.
	 *
	 * @return a scene context covering all channels
	 */
	public AudioSceneContext getContext() {
		return getContext(Collections.emptyList());
	}

	/**
	 * Returns an {@link AudioSceneContext} configured for the specified channel.
	 *
	 * @param channel the channel descriptor
	 * @return a scene context for the given channel
	 */
	public AudioSceneContext getContext(ChannelInfo channel) {
		return getContext(Collections.singletonList(channel));
	}

	/**
	 * Returns an {@link AudioSceneContext} configured for the specified list of channels.
	 * At most one channel may be provided; passing an empty list returns a context with
	 * no channel-specific sections.
	 *
	 * @param channels the list of channel descriptors (must have zero or one element)
	 * @return a fully configured scene context
	 */
	public AudioSceneContext getContext(List<ChannelInfo> channels) {
		if (channels.size() > 1) {
			throw new IllegalArgumentException();
		}

		if (automationLevel == null) {
			Evaluable<PackedCollection> level = automation.getAggregatedValueAt(
						x(),
						c(y(6), 0),
						c(y(6), 1),
						c(y(6), 2),
						c(y(6), 3),
						c(y(6), 4),
						c(y(6), 5),
						c(0.0))
					.get();
			automationLevel = gene -> position ->
					func(shape(1),
							args -> level.evaluate(position.evaluate(args), gene), false, false);
		}

		AudioSceneContext context = new AudioSceneContext();
		context.setChannels(channels);
		context.setMeasures(getTotalMeasures());
		context.setFrames(getTotalSamples());
		context.setFrameForPosition(pos -> (int) (pos * getMeasureSamples()));
		context.setTimeForDuration(len -> len * getMeasureDuration());
		context.setScaleForPosition(getChordProgression()::forPosition);
		context.setAutomationLevel(automationLevel);
		context.setActivityBias(patternActivityBias);

		if (!channels.isEmpty()) {
			context.setSections(sections.getChannelSections(channels.get(0)));
		}

		return context;
	}

	/**
	 * Serializes the current scene configuration into an {@link AudioSceneLoader.Settings} object.
	 *
	 * @return a settings snapshot of this scene's current state
	 */
	public AudioSceneLoader.Settings getSettings() {
		AudioSceneLoader.Settings settings = new AudioSceneLoader.Settings();
		settings.setBpm(getBPM());
		settings.setMeasureSize(getMeasureSize());
		settings.setTotalMeasures(getTotalMeasures());
		settings.getBreaks().addAll(time.getResets());
		settings.getSections().addAll(sections.getSections()
				.stream().map(s -> new AudioSceneLoader.Settings.Section(s.getPosition(), s.getLength())).collect(Collectors.toList()));

		if (library != null && library.getRoot() != null) {
			settings.setLibraryRoot(library.getRoot().getResourcePath());
		}

		settings.setChordProgression(progression.getSettings());
		settings.setPatternSystem(patterns.getSettings());
		settings.setChannelNames(channelNames);
		settings.setWetChannels(getEfxManager().getWetChannels());
		settings.setReverbChannels(getMixdownManager().getReverbChannels());
		settings.setGeneration(generation.getSettings());

		return settings;
	}

	/**
	 * Applies the given settings to this scene using the default library provider.
	 *
	 * @param settings the settings to apply
	 */
	public void setSettings(AudioSceneLoader.Settings settings) { setSettings(settings, this::createLibrary, null); }

	/**
	 * Applies the given settings to this scene with a custom library provider and optional
	 * progress callback.
	 *
	 * @param settings        the settings to apply
	 * @param libraryProvider a function that creates an {@link AudioLibrary} from a root path
	 * @param progress        optional consumer that receives library loading progress; may be {@code null}
	 */
	public void setSettings(AudioSceneLoader.Settings settings,
							Function<String, AudioLibrary> libraryProvider,
							DoubleConsumer progress) {
		setBPM(settings.getBpm());
		setMeasureSize(settings.getMeasureSize());
		setTotalMeasures(settings.getTotalMeasures());

		time.getResets().clear();
		settings.getBreaks().forEach(time::addReset);
		settings.getSections().forEach(s -> sections.addSection(s.getPosition(), s.getLength()));

		setLibrary(libraryProvider.apply(settings.getLibraryRoot()), progress);

		progression.setSettings(settings.getChordProgression());
		patterns.setSettings(settings.getPatternSystem());

		channelNames.clear();
		if (settings.getChannelNames() != null) channelNames.addAll(settings.getChannelNames());

		getEfxManager().getWetChannels().clear();
		getSectionManager().getWetChannels().clear();
		if (settings.getWetChannels() != null) {
			getEfxManager().getWetChannels().addAll(settings.getWetChannels());
			getSectionManager().getWetChannels().addAll(settings.getWetChannels());
		}

		getMixdownManager().getReverbChannels().clear();
		if (settings.getReverbChannels() != null) {
			getMixdownManager().getReverbChannels().addAll(settings.getReverbChannels());
		}

		generation.setSettings(settings.getGeneration());

		if (tuning != null) {
			setTuning(tuning);
		}
	}

	/**
	 * Notifies all registered duration listeners with the current total duration in seconds.
	 * Called whenever tempo, measure size, or total measure count changes.
	 */
	protected void triggerDurationChange() {
		durationListeners.forEach(l -> l.accept(getTotalDuration()));
	}

	/**
	 * Returns {@code true} if any pattern choice in this scene references the given
	 * resource (by canonical file path).
	 *
	 * @param canonicalPath the canonical file path to check
	 * @return {@code true} if the resource is in use by at least one pattern choice
	 */
	public boolean checkResourceUsed(String canonicalPath) {
		return getPatternManager().getChoices().stream().anyMatch(p -> p.checkResourceUsed(canonicalPath));
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	/**
	 * Creates cells for the specified channels with the given buffer configuration.
	 * The returned cell graph drives one buffer per {@code tick().get().run()} call.
	 *
	 * @param output        the audio output to write to
	 * @param channels      the channel indices to render
	 * @param bufferSize    frames per render buffer
	 * @param frameSupplier supplies the current frame position for rendering
	 * @param waveCellFrame external frame producer for WaveCells in the effects pipeline,
	 *                      so the frame position within each buffer is controlled by the
	 *                      runner loop rather than WaveCell's internal clock
	 * @return cells with pattern rendering and effects
	 */
	public Cells getCells(MultiChannelAudioOutput output,
						  List<Integer> channels,
						  int bufferSize,
						  IntSupplier frameSupplier,
						  Producer<PackedCollection> waveCellFrame) {
		long start = System.nanoTime();
		try {
			setup = new OperationList("AudioScene Setup");
			renderCells = new ArrayList<>();
			addCommonSetup(setup);
			setup.add(() -> () -> patterns.setTuning(tuning));
			setup.add(sections.setup());

			// Consolidate render buffers into one root so the compiled Loop collapses
			// all PatternAudioBuffer arguments into a single kernel argument.
			consolidateRenderBuffers(channels.size(), bufferSize);
			efx.consolidateFilterBuffers(channels.size(), bufferSize);

			if (activeCells != null) {
				activeCells.destroy();
				activeCells = null;
			}

			CellList cells = cells(
					getPatternCells(output, channels, ChannelInfo.StereoChannel.LEFT,
							bufferSize, frameSupplier, setup, waveCellFrame),
					getPatternCells(output, channels, ChannelInfo.StereoChannel.RIGHT,
							bufferSize, frameSupplier, setup, waveCellFrame));

			cells.addSetup(() -> setup);
			activeCells = cells;
			return cells.addRequirement(time::tick);
		} finally {
			getCellsTime.addEntry(System.nanoTime() - start);
		}
	}

	/**
	 * Pre-allocates a single contiguous buffer for all {@link PatternAudioBuffer}
	 * output buffers.
	 *
	 * <p>Each {@link PatternAudioBuffer} receives a delegate (range) into this
	 * consolidated buffer instead of its own independent {@link PackedCollection}.
	 * When the compiled {@code Loop} scope collects arguments, the scope's
	 * deduplication resolves each delegate to the shared root, collapsing all
	 * render buffer arguments into a single kernel argument.</p>
	 *
	 * <p>The total number of render cells is {@code channelCount x 4}
	 * (MAIN + WET voicing x LEFT + RIGHT stereo).</p>
	 *
	 * @param channelCount number of audio channels
	 * @param bufferSize   frames per render buffer
	 */
	private void consolidateRenderBuffers(int channelCount, int bufferSize) {
		int totalRenderCells = channelCount * 4;
		consolidatedRenderBuffer = new PackedCollection(bufferSize * totalRenderCells);
		renderBufferIndex = 0;
	}

	/**
	 * Creates pattern cells with optional external frame control for WaveCells.
	 *
	 * @param output         the audio output
	 * @param channels       channel indices to render
	 * @param audioChannel   LEFT or RIGHT stereo channel
	 * @param bufferSize     frames per render buffer
	 * @param frameSupplier  current frame position supplier for pattern rendering
	 * @param setup          the setup OperationList to accumulate operations in
	 * @param waveCellFrame  external frame producer for WaveCells, or null for internal clock
	 * @return CellList containing all channel cells for this stereo channel
	 */
	private CellList getPatternCells(MultiChannelAudioOutput output,
									 List<Integer> channels,
									 ChannelInfo.StereoChannel audioChannel,
									 int bufferSize,
									 IntSupplier frameSupplier,
									 OperationList setup,
									 Producer<PackedCollection> waveCellFrame) {
		int[] idx = channels.stream().mapToInt(i -> i).toArray();
		CellList main = all(idx.length, i ->
				getPatternChannel(new ChannelInfo(idx[i], ChannelInfo.Voicing.MAIN, audioChannel),
						bufferSize, frameSupplier, setup, waveCellFrame));

		// Skip WET cells when enableEfx is false — unused cells add kernel arguments and
		// MixdownManager creates unreachable efx branches. null routes to the fast path.
		CellList wet;
		if (!MixdownManager.enableEfx) {
			wet = null;
		} else {
			wet = all(idx.length, i ->
					getPatternChannel(new ChannelInfo(idx[i], ChannelInfo.Voicing.WET, audioChannel),
							bufferSize, frameSupplier, setup, waveCellFrame));
		}

		return mixdown.cells(main, wet, riser.getRise(bufferSize),
				output, audioChannel, i -> idx[i]);
	}

	/**
	 * Creates a CellList for a single pattern channel using {@link PatternAudioBuffer}.
	 *
	 * <p>This unified method replaces the former separate offline and real-time
	 * channel methods. Both paths now use {@link PatternAudioBuffer}; they differ
	 * only in buffer size and frame supplier:</p>
	 * <ul>
	 *   <li><strong>Offline:</strong> bufferSize = totalFrames, frameSupplier = () -> 0</li>
	 *   <li><strong>Real-time:</strong> bufferSize = 1024, dynamic frameSupplier</li>
	 * </ul>
	 *
	 * <p>The setup OperationList receives both {@link PatternAudioBuffer#setup()} and
	 * {@link PatternAudioBuffer#prepareBatch()} for the created render cell, so the first
	 * buffer is pre-rendered when setup runs. For real-time rendering, subsequent
	 * buffers are rendered via {@link PatternAudioBuffer#prepareBatch()} calls in
	 * {@link #runnerRealTime}.</p>
	 *
	 * @param channel       the channel (index, voicing, stereo channel)
	 * @param bufferSize    frames per render buffer
	 * @param frameSupplier supplies the current frame position
	 * @param setup         the setup OperationList (render cell setup and initial render are added here)
	 * @return CellList with effects applied, ready for mixdown
	 */
	public CellList getPatternChannel(ChannelInfo channel,
									  int bufferSize,
									  IntSupplier frameSupplier,
									  OperationList setup) {
		return getPatternChannel(channel, bufferSize, frameSupplier, setup, null);
	}

	/**
	 * Creates a CellList for a single pattern channel with optional external frame control.
	 *
	 * <p>When {@code waveCellFrame} is provided, the WaveCells in the effects pipeline
	 * use external frame control instead of internal clocks. This is essential for
	 * real-time rendering where the frame position within each buffer must be controlled
	 * by the runner loop.</p>
	 *
	 * @param channel        the channel (index, voicing, stereo channel)
	 * @param bufferSize     frames per render buffer
	 * @param frameSupplier  supplies the current frame position for pattern rendering
	 * @param setup          the setup OperationList
	 * @param waveCellFrame  external frame producer for WaveCells, or null for internal clock
	 * @return CellList with effects applied, ready for mixdown
	 */
	public CellList getPatternChannel(ChannelInfo channel,
									  int bufferSize,
									  IntSupplier frameSupplier,
									  OperationList setup,
									  Producer<PackedCollection> waveCellFrame) {
		Supplier<AudioSceneContext> ctx = () -> getContext(List.of(channel));

		PackedCollection outputBuffer = null;
		if (consolidatedRenderBuffer != null) {
			outputBuffer = consolidatedRenderBuffer.range(
					shape(bufferSize), renderBufferIndex * bufferSize);
			renderBufferIndex++;
		}

		PatternAudioBuffer renderCell = new PatternAudioBuffer(
				patterns, ctx, channel, bufferSize, frameSupplier, outputBuffer);

		setup.add(renderCell.setup());
		setup.add(renderCell.prepareBatch());

		CellList cells = efx.apply(channel, renderCell.getOutputProducer(),
				getTotalDuration(), setup, waveCellFrame);
		renderCells.add(renderCell);

		return cells;
	}

	/**
	 * Adds the common setup operations shared by all runner variants.
	 */
	private void addCommonSetup(OperationList setup) {
		setup.add(automation.setup());
		if (MixdownManager.enableRiser) setup.add(riser.setup());
		setup.add(mixdown.setup());
		setup.add(time.setup());
	}

	/**
	 * Creates a real-time runner that renders patterns incrementally.
	 *
	 * <p>The runner renders patterns incrementally during the tick phase via
	 * {@link PatternAudioBuffer}, enabling true real-time streaming. The buffer
	 * size determines how many frames are rendered per batch — use
	 * {@link #DEFAULT_REALTIME_BUFFER_SIZE} for a reasonable default.</p>
	 *
	 * @param output     the audio output to write to
	 * @param bufferSize frames per buffer
	 * @return a TemporalCellular for real-time playback
	 *
	 * @see PatternAudioBuffer
	 */
	public TemporalCellular runnerRealTime(MultiChannelAudioOutput output, int bufferSize) {
		return runnerRealTime(output, null, bufferSize);
	}

	/**
	 * Creates a real-time runner for specific channels.
	 *
	 * <p>Three phases per tick: prepare (Java) renders pattern audio via
	 * {@link PatternAudioBuffer#prepareBatch()}; tick (compiled) applies effects per frame;
	 * advance increments the frame counter. The compiled kernel is structurally independent
	 * of the genome — only the {@link PackedCollection} contents change on
	 * {@link #assignGenome}, so the runner can be reused without recompilation.</p>
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render, or null for all
	 * @param bufferSize frames per buffer
	 * @return a TemporalCellular for real-time playback
	 */
	public TemporalCellular runnerRealTime(MultiChannelAudioOutput output,
										   List<Integer> channels,
										   int bufferSize) {
		final int[] currentFrame = {0};

		if (channels == null) {
			channels = IntStream.range(0, getChannelCount()).boxed().collect(Collectors.toList());
		}

		// Create per-buffer frame index for WaveCell external frame control
		// This tracks position 0 to bufferSize-1 within each buffer
		PackedCollection bufferFrameIndex = new PackedCollection(1);
		Producer<PackedCollection> bufferFrameProducer = cp(bufferFrameIndex);

		CellList cells = (CellList) getCells(output, channels, bufferSize,
				() -> currentFrame[0], bufferFrameProducer);

		// Per-frame operation (must be compilable)
		Supplier<Runnable> frameOp = cells.tick();

		// Create loop body: tick + increment buffer frame index
		OperationList loopBody = new OperationList("RealTime Per-Frame Body");
		loopBody.add(frameOp);
		// Increment buffer frame index: bufferFrameIndex = bufferFrameIndex + 1
		loopBody.add(a(1, cp(bufferFrameIndex), c(1.0).add(cp(bufferFrameIndex))));

		return new TemporalCellular() {
			@Override
			public Supplier<Runnable> setup() {
				return cells.setup();
			}

			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("AudioScene RealTime Runner Tick");

				// OUTSIDE LOOP: Reset buffer frame index and prepare pattern data
				tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));
				for (PatternAudioBuffer renderCell : renderCells) {
					tick.add(renderCell.prepareBatch());
				}

				// INSIDE LOOP: Compilable per-frame processing with frame index increment
				tick.add(loop(loopBody, bufferSize));

				// AFTER LOOP: Advance global frame position
				tick.add(() -> () -> currentFrame[0] += bufferSize);
				return tick;
			}

			@Override
			public void reset() {
				currentFrame[0] = 0;
				cells.reset();
				// Rewind the global clock so time-driven envelopes (volume,
				// filter, AutomationManager outputs) start fresh on the next
				// genome. Without this, evaluating multiple genomes in
				// sequence makes every genome after the first run with the
				// clock parked in the post-decay region of the volume
				// envelope (gene 4 has scale = -1, a fade-out), so pattern
				// channels come out near-silent.
				time.getClock().setFrame(0);
			}
		};
	}

	/**
	 * Pre-evaluates all note audio to warm the kernel compilation cache.
	 *
	 * <p>Call after construction and genome assignment, before starting the real-time loop,
	 * to trigger kernel compilation for all unique instrument chains upfront.</p>
	 *
	 * @return the number of notes evaluated during warmup
	 * @see PatternSystemManager#warmNoteCache(java.util.function.Function)
	 */
	public int warmNoteCache() {
		patterns.setTuning(tuning);
		return patterns.warmNoteCache(channel -> getContext(List.of(channel)));
	}

	/**
	 * Serializes the current scene settings to a JSON file.
	 *
	 * @param file the target file to write settings to
	 * @throws IOException if the file cannot be written
	 */
	public void saveSettings(File file) throws IOException {
		AudioSceneLoader.defaultMapper().writeValue(file, getSettings());
	}

	/**
	 * Loads and applies scene settings from the given JSON file using the default library
	 * provider. Falls back to default settings if the file does not exist or cannot be parsed.
	 *
	 * @param file the settings file to load; may be {@code null} to use defaults
	 */
	public void loadSettings(File file) {
		loadSettings(file, this::createLibrary, null);
	}

	/**
	 * Loads and applies scene settings from the given JSON file using the provided library
	 * provider and optional progress callback. Falls back to default settings if the file
	 * does not exist or cannot be parsed.
	 *
	 * @param file            the settings file to load; may be {@code null} to use defaults
	 * @param libraryProvider a function that creates an {@link AudioLibrary} from a root path
	 * @param progress        optional loading progress consumer; may be {@code null}
	 */
	public void loadSettings(File file, Function<String, AudioLibrary> libraryProvider, DoubleConsumer progress) {
		if (file != null && file.exists()) {
			try (FileInputStream in = new FileInputStream(file)) {
				setSettings(AudioSceneLoader.defaultMapper().readValue(
						MigrationClassLoader.migrateStream(in),
						AudioSceneLoader.Settings.class), libraryProvider, progress);
				return;
			} catch (Exception e) {
				warn(e.getMessage(), e);
			}
		}

		setSettings(AudioSceneLoader.Settings.defaultSettings(getChannelCount(),
				DEFAULT_PATTERNS_PER_CHANNEL,
				DEFAULT_ACTIVE_PATTERNS,
				DEFAULT_LAYERS,
				DEFAULT_LAYER_SCALE,
				DEFAULT_DURATION), libraryProvider, progress);
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		getSectionManager().destroy();

		if (activeCells != null) {
			activeCells.destroy();
			activeCells = null;
		}

		if (consolidatedRenderBuffer != null) {
			consolidatedRenderBuffer.destroy();
			consolidatedRenderBuffer = null;
		}

		efx.destroyConsolidatedBuffers();
		activeInstances.remove(this);
	}

	/**
	 * Destroys all {@link AudioScene} instances that have not been explicitly
	 * destroyed yet, freeing their native memory allocations.
	 *
	 * <p>This is primarily useful in test environments where multiple scenes
	 * are created across test methods without explicit cleanup. Each scene's
	 * {@link #destroy()} method is called to release its cell graph,
	 * consolidated buffers, and delay line memory.</p>
	 */
	public static void destroyAll() {
		List<AudioScene<?>> scenes = new ArrayList<>(activeInstances);
		for (AudioScene<?> scene : scenes) {
			scene.destroy();
		}
	}

	/**
	 * Creates a shallow clone of this scene. The clone shares the same audio library
	 * and pattern choices but gets a fresh settings configuration.
	 *
	 * @return a new {@code AudioScene} that mirrors this scene's configuration
	 */
	@Override
	public AudioScene<T> clone() {
		AudioScene<T> clone = new AudioScene<>(scene, bpm,
				channelCount, delayLayerCount, sampleRate,
				getPatternManager().getChoices(),
				getGenerationManager().getGenerationProvider());

		// Directly assign the library (processing is redundant)
		clone.library = library;

		// Retrieve the settings, but avoid repeating library processing
		AudioSceneLoader.Settings settings = getSettings();
		settings.setLibraryRoot(null);

		// Avoid redundant tuning assignment during assignment of settings
		clone.tuning = null;
		clone.setSettings(settings);
		clone.tuning = tuning;

		return clone;
	}

	/**
	 * Convenience factory method that creates and fully configures an audio scene from files.
	 * Delegates to {@link AudioSceneLoader#load(String, String, String, double, int)}.
	 *
	 * @param settingsFile path to the JSON settings file, or {@code null} for defaults
	 * @param patternsFile path to the JSON patterns file
	 * @param libraryRoot  path to the root sample library directory, or {@code null}
	 * @param bpm          the initial tempo in beats per minute
	 * @param sampleRate   the audio sample rate in Hz
	 * @return a fully configured {@link AudioScene}
	 * @throws IOException if any file cannot be read
	 */
	public static AudioScene<?> load(String settingsFile, String patternsFile, String libraryRoot, double bpm, int sampleRate) throws IOException {
		return AudioSceneLoader.load(settingsFile, patternsFile, libraryRoot, bpm, sampleRate);
	}

	/**
	 * Factory method that creates a visually backed audio scene from files.
	 * Delegates to {@link AudioSceneLoader#load(Animation, String, String, String, double, int)}.
	 *
	 * @param scene        the optional visual scene; may be {@code null}
	 * @param settingsFile path to the JSON settings file, or {@code null} for defaults
	 * @param patternsFile path to the JSON patterns file
	 * @param libraryRoot  path to the root sample library directory, or {@code null}
	 * @param bpm          the initial tempo in beats per minute
	 * @param sampleRate   the audio sample rate in Hz
	 * @return a fully configured {@link AudioScene}
	 * @throws IOException if any file cannot be read
	 */
	public static AudioScene<?> load(Animation<?> scene, String settingsFile, String patternsFile, String libraryRoot, double bpm, int sampleRate) throws IOException {
		return AudioSceneLoader.load(scene, settingsFile, patternsFile, libraryRoot, bpm, sampleRate);
	}

	/**
	 * Returns the default genome variation operator that applies small Gaussian perturbations
	 * to a random subset of gene values. The rate and intensity are controlled by
	 * {@link #variationRate} and {@link #variationIntensity}.
	 *
	 * @return a {@link UnaryOperator} that produces a perturbed copy of the input genome
	 */
	public static UnaryOperator<ProjectedGenome> defaultVariation() {
		return genome -> {
			Random rand = new Random();
			return genome.variation(0, 1, variationRate,
					() -> variationIntensity * rand.nextGaussian());
		};
	}

	/**
	 * Delegates to {@link AudioSceneLoader#defaultMapper()}.
	 *
	 * @return a configured {@link ObjectMapper}
	 */
	public static ObjectMapper defaultMapper() {
		return AudioSceneLoader.defaultMapper();
	}

	/**
	 * Creates an {@link AudioLibrary} from the given root path, or returns {@code null}
	 * if the path is {@code null}.
	 *
	 * @param f the root directory path, or {@code null}
	 * @return an {@link AudioLibrary} or {@code null}
	 */
	private AudioLibrary createLibrary(String f) {
		if (f == null) return null;

		return new AudioLibrary(createTree(f), getSampleRate());
	}

	/**
	 * Creates a {@link FileWaveDataProviderNode} for the given directory path.
	 *
	 * @param f the root directory path
	 * @return a {@link FileWaveDataProviderTree} backed by the directory
	 */
	private FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> createTree(String f) {
		return new FileWaveDataProviderNode(new File(f));
	}
}
