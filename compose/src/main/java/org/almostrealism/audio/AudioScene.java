/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.cycle.Setup;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.ModelEntity;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.arrange.AutomationManager;
import org.almostrealism.audio.arrange.EfxManager;
import org.almostrealism.audio.arrange.GlobalTimeManager;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.arrange.RiseManager;
import org.almostrealism.audio.arrange.SceneSectionManager;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.generative.GenerationManager;
import org.almostrealism.audio.generative.GenerationProvider;
import org.almostrealism.audio.generative.NoOpGenerationProvider;
import org.almostrealism.audio.health.HealthComputationAdapter;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.pattern.ChordProgressionManager;
import org.almostrealism.audio.pattern.NoteAudioChoiceList;
import org.almostrealism.audio.pattern.PatternSystemManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.KeyPosition;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@ModelEntity
public class AudioScene<T extends ShadableSurface> implements Setup, Destroyable, CellFeatures {
	public static final Console console = CellFeatures.console.child();
	private static final TimingMetric getCellsTime = console.timing("getCells");

	public static final int DEFAULT_SOURCE_COUNT = 6;
	public static final int DEFAULT_DELAY_LAYERS = 3;
	public static final int DEFAULT_PATTERNS_PER_CHANNEL = 6;
	public static final int MAX_SCENE_SECTIONS = 16;
	public static final IntUnaryOperator DEFAULT_ACTIVE_PATTERNS;
	public static final IntUnaryOperator DEFAULT_LAYERS;
	public static final IntToDoubleFunction DEFAULT_LAYER_SCALE;
	public static final IntUnaryOperator DEFAULT_DURATION;

	public static double variationRate = 0.1;
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

	private final int sampleRate;
	private double bpm;
	private final int channelCount;
	private final int delayLayerCount;
	private int measureSize = 4;
	private int totalMeasures = 1;

	private final Animation<T> scene;

	private final GlobalTimeManager time;
	private KeyboardTuning tuning;
	private final ChordProgressionManager progression;

	private AudioLibrary library;
	private final PatternSystemManager patterns;
	private Map<ChannelInfo, PackedCollection> patternDestinations;
	private final List<String> channelNames;
	private double patternActivityBias;

	private final SceneSectionManager sections;
	private final AutomationManager automation;
	private final EfxManager efx;
	private final RiseManager riser;
	private final MixdownManager mixdown;

	private final GenerationManager generation;

	private final ProjectedGenome genome;
	
	private OperationList setup;
	private Function<PackedCollection, Factor<PackedCollection>> automationLevel;

	private final List<Consumer<Frequency>> tempoListeners;
	private final List<DoubleConsumer> durationListeners;

	public AudioScene(Animation<T> scene, double bpm, int sampleRate) {
		this(scene, bpm, DEFAULT_SOURCE_COUNT, DEFAULT_DELAY_LAYERS, sampleRate);
	}

	public AudioScene(double bpm, int channels, int delayLayers,
					  int sampleRate) {
		this(null, bpm, channels, delayLayers, sampleRate);
	}

	public AudioScene(Animation<T> scene, double bpm, int channels, int delayLayers,
					  int sampleRate) {
		this(scene, bpm, channels, delayLayers, sampleRate, new ArrayList<>(), new NoOpGenerationProvider());
	}

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

		addDurationListener(duration -> destroyPatternDestinations());

		this.automation = new AutomationManager(genome.addChromosome(), time.getClock(),
											this::getMeasureDuration, getSampleRate());
		this.efx = new EfxManager(genome.addChromosome(), channels,
									automation, this::getBeatDuration, getSampleRate());
		this.riser = new RiseManager(genome.addChromosome(),
				() -> getContext(new ChannelInfo(0, ChannelInfo.Type.RISE, null)), getSampleRate());
		this.mixdown = new MixdownManager(genome.addChromosome(),
									channels, delayLayers,
									automation, time.getClock(), getSampleRate());

		this.generation = new GenerationManager(patterns, generation);
	}

	@Deprecated
	public void setBPM(double bpm) {
		this.bpm = bpm;
		tempoListeners.forEach(l -> l.accept(Frequency.forBPM(bpm)));
		triggerDurationChange();
	}

	@Deprecated
	public double getBPM() { return this.bpm; }

	public void setTempo(Frequency tempo) {
		this.bpm = tempo.asBPM();
		tempoListeners.forEach(l -> l.accept(tempo));
		triggerDurationChange();
	}

	public Frequency getTempo() { return Frequency.forBPM(bpm); }

	public void setTuning(KeyboardTuning tuning) {
		this.tuning = tuning;
		patterns.setTuning(tuning);
	}

	public KeyboardTuning getTuning() { return tuning; }

	public AudioLibrary getLibrary() { return library; }

	public void setLibrary(AudioLibrary library) {
		setLibrary(library, null);
	}

	public void setLibrary(AudioLibrary library, DoubleConsumer progress) {
		this.library = library;

		if (getLibrary() != null) {
			patterns.setTree(getLibrary().getRoot(), progress);
		}
	}

	public void setLibraryRoot(FileWaveDataProviderTree tree) {
		setLibraryRoot(tree, null);
	}

	public void setLibraryRoot(FileWaveDataProviderTree tree, DoubleConsumer progress) {
		setLibrary(new AudioLibrary(tree, getSampleRate()), progress);
	}

	public void loadPatterns(String patternsFile) throws IOException {
		NoteAudioChoiceList choices = defaultMapper()
				.readValue(new File(patternsFile), NoteAudioChoiceList.class);
		getPatternManager().getChoices().addAll(choices);
	}

	public Animation<T> getScene() { return scene; }

	public ProjectedGenome getGenome() { return new ProjectedGenome(genome.getParameters()); }

	public void assignGenome(ProjectedGenome genome) {
		this.genome.assignTo(genome.getParameters());
		this.progression.refreshParameters();
		this.patterns.refreshParameters();
	}

	public void addSection(int position, int length) {
		sections.addSection(position, length);
	}
	public void addBreak(int measure) { time.addReset(measure); }

	public GlobalTimeManager getTimeManager() { return time; }
	public SceneSectionManager getSectionManager() { return sections; }
	public ChordProgressionManager getChordProgression() { return progression; }
	public PatternSystemManager getPatternManager() { return patterns; }
	public AutomationManager getAutomationManager() { return automation; }
	public EfxManager getEfxManager() { return efx; }
	public MixdownManager getMixdownManager() { return mixdown; }
	public GenerationManager getGenerationManager() { return generation; }

	public void addTempoListener(Consumer<Frequency> listener) { this.tempoListeners.add(listener); }
	public void removeTempoListener(Consumer<Frequency> listener) { this.tempoListeners.remove(listener); }

	public void addDurationListener(DoubleConsumer listener) { this.durationListeners.add(listener); }
	public void removeDurationListener(DoubleConsumer listener) { this.durationListeners.remove(listener); }

	public int getChannelCount() { return channelCount; }
	public int getDelayLayerCount() { return delayLayerCount; }

	public double getPatternActivityBias() { return patternActivityBias; }
	public void setPatternActivityBias(double patternActivityBias) { this.patternActivityBias = patternActivityBias; }

	public List<String> getChannelNames() { return channelNames; }

	public double getBeatDuration() { return getTempo().l(1); }

	public void setMeasureSize(int measureSize) { this.measureSize = measureSize; triggerDurationChange(); }
	public int getMeasureSize() { return measureSize; }
	public double getMeasureDuration() { return getTempo().l(getMeasureSize()); }
	public int getMeasureSamples() { return (int) (getMeasureDuration() * getSampleRate()); }

	public void setTotalMeasures(int measures) { this.totalMeasures = measures; triggerDurationChange(); }
	public int getTotalMeasures() { return totalMeasures; }
	public int getTotalBeats() { return totalMeasures * measureSize; }
	public double getTotalDuration() { return getTempo().l(getTotalBeats()); }
	public int getTotalSamples() { return (int) (getTotalDuration() * getSampleRate()); }
	public int getAvailableSamples() {
		return Math.min(HealthComputationAdapter.standardDurationFrames, getTotalSamples());
	}

	public int getSampleRate() { return sampleRate; }

	public AudioSceneContext getContext() {
		return getContext(Collections.emptyList());
	}

	public AudioSceneContext getContext(ChannelInfo channel) {
		return getContext(Collections.singletonList(channel));
	}

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

			if (patternDestinations != null)
				context.setDestination(patternDestinations.get(channels.get(0)));
		}

		return context;
	}

	public Settings getSettings() {
		Settings settings = new Settings();
		settings.setBpm(getBPM());
		settings.setMeasureSize(getMeasureSize());
		settings.setTotalMeasures(getTotalMeasures());
		settings.getBreaks().addAll(time.getResets());
		settings.getSections().addAll(sections.getSections()
				.stream().map(s -> new Settings.Section(s.getPosition(), s.getLength())).collect(Collectors.toList()));

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

	public void setSettings(Settings settings) { setSettings(settings, this::createLibrary, null); }

	public void setSettings(Settings settings,
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

	protected void triggerDurationChange() {
		durationListeners.forEach(l -> l.accept(getTotalDuration()));
	}

	public boolean checkResourceUsed(String canonicalPath) {
		return getPatternManager().getChoices().stream().anyMatch(p -> p.checkResourceUsed(canonicalPath));
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	public Cells getCells(MultiChannelAudioOutput output) {
		long start = System.nanoTime();

		try {
			return getCells(output,
					IntStream.range(0, getChannelCount())
							.boxed().collect(Collectors.toList()));
		} finally {
			getCellsTime.addEntry(System.nanoTime() - start);
		}
	}

	public Cells getCells(MultiChannelAudioOutput output, List<Integer> channels) {
		CellList cells;

		setup = new OperationList("AudioScene Setup");
		setup.add(automation.setup());

		if (MixdownManager.enableRiser)
			setup.add(riser.setup());

		setup.add(mixdown.setup());
		setup.add(time.setup());

		cells = getPatternCells(output, channels);
		return cells.addRequirement(time::tick);
	}

	public CellList getPatternCells(MultiChannelAudioOutput output,
									List<Integer> channels) {
		return cells(
				getPatternCells(output, channels, ChannelInfo.StereoChannel.LEFT),
				getPatternCells(output, channels, ChannelInfo.StereoChannel.RIGHT));
	}

	public CellList getPatternCells(MultiChannelAudioOutput output,
									List<Integer> channels,
									ChannelInfo.StereoChannel audioChannel) {
		int totalSamples;
		if (getTotalSamples() > HealthComputationAdapter.standardDurationFrames) {
			warn("AudioScene arrangement extends beyond the standard duration");
			totalSamples = HealthComputationAdapter.standardDurationFrames;
		} else {
			totalSamples = getTotalSamples();
		}

		int[] channelIndex = channels.stream().mapToInt(i -> i).toArray();
		CellList main = all(channelIndex.length, i ->
				getPatternChannel(new ChannelInfo(channelIndex[i], ChannelInfo.Voicing.MAIN, audioChannel), totalSamples, setup));
		CellList wet = all(channelIndex.length, i ->
				getPatternChannel(new ChannelInfo(channelIndex[i], ChannelInfo.Voicing.WET, audioChannel), totalSamples, setup));
		return mixdown.cells(main, wet, riser.getRise(totalSamples), output, audioChannel, i -> channelIndex[i]);
	}

	public CellList getPatternChannel(ChannelInfo channel, int frames, OperationList setup) {
		OperationList patternSetup = new OperationList("PatternChannel Setup");
		patternSetup.add(() -> () -> patterns.setTuning(tuning));
		patternSetup.add(sections.setup());
		patternSetup.add(getPatternSetup(channel));

		TraversalPolicy audioShape =
				(frames > getAvailableSamples() ? shape(getAvailableSamples()) : shape(frames))
						.traverseEach();

		sections.getChannelSections(channel).stream()
				.map(section -> {
					int pos = section.getPosition() * getMeasureSamples();
					int len = section.getLength() * getMeasureSamples();

					if (audioShape.getTotalSize() < pos + len) {
						warn("Section at position " + pos +
								" extends beyond the end of the pattern destination (" +
								audioShape.getTotalSize() + " frames)");
						return new OperationList("Section Processing (Invalid Size)");
					} else {
						Producer<PackedCollection> sectionAudio =
								func(audioShape, args ->
										patternDestinations.get(channel).range(shape(len), pos), false);
						return section.process(sectionAudio, sectionAudio);
					}
				})
				.forEach(patternSetup::add);

		setup.add(patternSetup);

		Producer<PackedCollection> result =
				func(audioShape, args -> patternDestinations.get(channel).range(audioShape), false);
		return efx.apply(channel, result, getTotalDuration(), setup);
	}

	public Supplier<Runnable> getPatternSetup(ChannelInfo channel) {
		Supplier<AudioSceneContext> ctx = () -> {
			refreshPatternDestination(channel, false);
			return getContext(List.of(channel));
		};

		OperationList op = new OperationList("AudioScene Pattern Setup (Channel " + channel + ")");
		op.add(() -> () -> refreshPatternDestination(channel, true));
		op.add(patterns.sum(ctx, channel));
		return op;
	}

	public TemporalCellular runner(MultiChannelAudioOutput output) {
		return runner(output, null);
	}

	public TemporalCellular runner(MultiChannelAudioOutput output,
								   List<Integer> channels) {
		Cells cells = channels == null ?
				getCells(output) : getCells(output, channels);

		return new TemporalCellular() {
			@Override
			public Supplier<Runnable> setup() {
				OperationList setup = new OperationList("AudioScene Runner Setup");
				setup.addAll((List) AudioScene.this.setup());
				setup.addAll((List) cells.setup());
				return setup.flatten();
			}

			@Override
			public Supplier<Runnable> tick() {
				return cells.tick();
			}

			@Override
			public void reset() {
				cells.reset();
			}
		};
	}

	private void refreshPatternDestination(ChannelInfo channel, boolean clear) {
		if (patternDestinations == null) {
			int frames = Math.min(HealthComputationAdapter.standardDurationFrames, getTotalSamples());

			patternDestinations = new HashMap<>();
			for (int i = 0; i < getChannelCount(); i++) {
				patternDestinations.put(new ChannelInfo(i, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT),
						new PackedCollection(frames));
				patternDestinations.put(new ChannelInfo(i, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.RIGHT),
						new PackedCollection(frames));
				patternDestinations.put(new ChannelInfo(i, ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.LEFT),
						new PackedCollection(frames));
				patternDestinations.put(new ChannelInfo(i, ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.RIGHT),
						new PackedCollection(frames));
			}

			if (MixdownManager.enableRiser) {
				patternDestinations.put(new ChannelInfo(0, ChannelInfo.Type.RISE, null),
						new PackedCollection(Math.min(HealthComputationAdapter.standardDurationFrames, getTotalSamples())));
			}
		} else if (clear) {
			patternDestinations.get(channel).clear();
		}
	}

	private void destroyPatternDestinations() {
		if (patternDestinations != null) {
			patternDestinations.values().forEach(PackedCollection::destroy);
			patternDestinations = null;
		}
	}

	public void saveSettings(File file) throws IOException {
		defaultMapper().writeValue(file, getSettings());
	}

	public void loadSettings(File file) {
		loadSettings(file, this::createLibrary, null);
	}

	public void loadSettings(File file, Function<String, AudioLibrary> libraryProvider, DoubleConsumer progress) {
		if (file != null && file.exists()) {
			try {
				setSettings(defaultMapper().readValue(file, AudioScene.Settings.class), libraryProvider, progress);
				return;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		setSettings(Settings.defaultSettings(getChannelCount(),
				DEFAULT_PATTERNS_PER_CHANNEL,
				DEFAULT_ACTIVE_PATTERNS,
				DEFAULT_LAYERS,
				DEFAULT_LAYER_SCALE,
				DEFAULT_DURATION), libraryProvider, progress);
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		destroyPatternDestinations();
		getSectionManager().destroy();
	}

	public AudioScene<T> clone() {
		AudioScene<T> clone = new AudioScene<>(scene, bpm,
				channelCount, delayLayerCount, sampleRate,
				getPatternManager().getChoices(),
				getGenerationManager().getGenerationProvider());

		// Directly assign the library (processing is redundant)
		clone.library = library;

		// Retrieve the settings, but avoid repeating library processing
		Settings settings = getSettings();
		settings.setLibraryRoot(null);

		// Avoid redundant tuning assignment during assignment of settings
		clone.tuning = null;
		clone.setSettings(settings);
		clone.tuning = tuning;

		return clone;
	}

	public static AudioScene<?> load(String settingsFile, String patternsFile, String libraryRoot, double bpm, int sampleRate) throws IOException {
		return load(null, settingsFile, patternsFile, libraryRoot, bpm, sampleRate);
	}

	public static AudioScene<?> load(Animation<?> scene, String settingsFile, String patternsFile, String libraryRoot, double bpm, int sampleRate) throws IOException {
		AudioScene<?> audioScene = new AudioScene<>(scene, bpm, sampleRate);
		audioScene.loadPatterns(patternsFile);
		audioScene.setTuning(new DefaultKeyboardTuning());
		audioScene.loadSettings(settingsFile == null ? null : new File(settingsFile));
		if (libraryRoot != null) audioScene.setLibraryRoot(new FileWaveDataProviderNode(new File(libraryRoot)));
		return audioScene;
	}

	public static ObjectMapper defaultMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		SimpleModule module = new SimpleModule();
		module.addDeserializer(KeyPosition.class, keyPositionDeserializer(KeyPosition.class, KeyPosition::of));
		module.addDeserializer(WesternChromatic.class, keyPositionDeserializer(WesternChromatic.class, s -> WesternChromatic.valueOf(s)));
		mapper.registerModule(module);

		return mapper;
	}

	private static <T> StdDeserializer<T> keyPositionDeserializer(Class<T> clazz, Function<String, T> factory) {
		return new StdDeserializer<>(clazz) {
			@Override
			public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
				if (p.currentToken() == JsonToken.START_ARRAY) {
					p.nextToken(); // class name
					p.nextToken(); // value
					String value = p.getValueAsString();
					p.nextToken(); // END_ARRAY
					return factory.apply(value);
				}
				return factory.apply(p.getValueAsString());
			}
		};
	}

	public static UnaryOperator<ProjectedGenome> defaultVariation() {
		return genome -> {
			Random rand = new Random();
			return genome.variation(0, 1, variationRate,
					() -> variationIntensity * rand.nextGaussian());
		};
	}

	public static class Settings {
		private double bpm = 120;
		private int measureSize = 4;
		private int totalMeasures = 64;
		private List<Integer> breaks = new ArrayList<>();
		private List<Section> sections = new ArrayList<>();
		private String libraryRoot;

		private ChordProgressionManager.Settings chordProgression;
		private PatternSystemManager.Settings patternSystem;
		private List<String> channelNames;
		private List<Integer> wetChannels;
		private List<Integer> reverbChannels;

		private GenerationManager.Settings generation;

		public Settings() {
			patternSystem = new PatternSystemManager.Settings();
			generation = new GenerationManager.Settings();
		}

		public double getBpm() { return bpm; }
		public void setBpm(double bpm) { this.bpm = bpm; }

		public int getMeasureSize() { return measureSize; }
		public void setMeasureSize(int measureSize) { this.measureSize = measureSize; }

		public int getTotalMeasures() { return totalMeasures; }
		public void setTotalMeasures(int totalMeasures) { this.totalMeasures = totalMeasures; }

		public List<Integer> getBreaks() { return breaks; }
		public void setBreaks(List<Integer> breaks) { this.breaks = breaks; }

		public List<Section> getSections() { return sections; }
		public void setSections(List<Section> sections) { this.sections = sections; }

		public String getLibraryRoot() { return libraryRoot; }
		public void setLibraryRoot(String libraryRoot) { this.libraryRoot = libraryRoot; }

		public ChordProgressionManager.Settings getChordProgression() { return chordProgression; }
		public void setChordProgression(ChordProgressionManager.Settings chordProgression) { this.chordProgression = chordProgression; }
		
		public PatternSystemManager.Settings getPatternSystem() { return patternSystem; }
		public void setPatternSystem(PatternSystemManager.Settings patternSystem) { this.patternSystem = patternSystem; }

		public List<String> getChannelNames() { return channelNames; }
		public void setChannelNames(List<String> channelNames) { this.channelNames = channelNames; }

		public List<Integer> getWetChannels() { return wetChannels; }
		public void setWetChannels(List<Integer> wetChannels) { this.wetChannels = wetChannels; }

		public List<Integer> getReverbChannels() { return reverbChannels; }
		public void setReverbChannels(List<Integer> reverbChannels) { this.reverbChannels = reverbChannels; }

		public GenerationManager.Settings getGeneration() { return generation; }
		public void setGeneration(GenerationManager.Settings generation) { this.generation = generation; }

		public static class Section {
			private int position, length;

			public Section() { }

			public Section(int position, int length) {
				this.position = position;
				this.length = length;
			}

			public int getPosition() { return position; }
			public void setPosition(int position) { this.position = position; }

			public int getLength() { return length; }
			public void setLength(int length) { this.length = length; }
		}

		public static Settings defaultSettings(int channels, int patternsPerChannel,
											   IntUnaryOperator activePatterns,
											   IntUnaryOperator layersPerPattern,
											   IntToDoubleFunction minLayerScale,
											   IntUnaryOperator duration) {
			Settings settings = new Settings();
			settings.getSections().add(new Section(0, 16));
			settings.getSections().add(new Section(16, 16));
			settings.getSections().add(new Section(32, 8));
			settings.getBreaks().add(40);
			settings.getSections().add(new Section(40, 16));
			settings.getSections().add(new Section(56, 16));
			settings.getSections().add(new Section(72, 8));
			settings.getBreaks().add(80);
			settings.getSections().add(new Section(80, 64));
			settings.setTotalMeasures(144);
			settings.setChordProgression(ChordProgressionManager.Settings.defaultSettings());
			settings.setPatternSystem(PatternSystemManager.Settings
					.defaultSettings(channels, patternsPerChannel, activePatterns,
									layersPerPattern, minLayerScale, duration));
			settings.setChannelNames(List.of("Kick", "Drums", "Bass", "Harmony", "Lead", "Atmosphere"));
			settings.setWetChannels(List.of(2, 3, 4, 5));
			settings.setReverbChannels(List.of(1, 2, 3, 4, 5));
			return settings;
		}
	}

	private AudioLibrary createLibrary(String f) {
		if (f == null) return null;

		return new AudioLibrary(createTree(f), getSampleRate());
	}

	private FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> createTree(String f) {
		return new FileWaveDataProviderNode(new File(f));
	}
}
