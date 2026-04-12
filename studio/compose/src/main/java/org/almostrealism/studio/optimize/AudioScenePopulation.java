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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.CodeFeatures;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.Console;
import org.almostrealism.optimize.HealthCallable;
import org.almostrealism.optimize.Population;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Population implementation that manages a collection of genomes for evolutionary optimization
 * of an {@link AudioScene}. Each genome encodes scene parameters; enabling a genome assigns
 * those parameters to the scene for audio rendering and health evaluation.
 *
 * <p>Audio generation is performed via {@link #generate}, which iterates over all genomes
 * and produces per-genome WAV output files. Population state is persisted and restored as
 * JSON using {@link #store(OutputStream)} and {@link #read(InputStream)}.</p>
 */
public class AudioScenePopulation implements Population<PackedCollection, TemporalCellular>, Destroyable, CodeFeatures {

	/** The audio scene whose parameters are governed by the genomes in this population. */
	private final AudioScene<?> scene;

	/** The current list of genomes constituting this population. */
	private List<Genome<PackedCollection>> pop;

	/** The genome that is currently applied to the scene, or {@code null} if none is active. */
	private Genome currentGenome;

	/** The compiled temporal cell pipeline produced during initialization for audio rendering. */
	private TemporalCellular temporal;

	/** File path of the most recently generated audio output for the active genome. */
	private String outputPath;

	/** File handle to the most recently generated audio output; may be {@code null}. */
	private File outputFile;

	/**
	 * Creates a population for the given scene with an empty genome list.
	 *
	 * @param scene the audio scene this population will optimize
	 */
	public AudioScenePopulation(AudioScene<?> scene) {
		this(scene, new ArrayList<>());
	}

	/**
	 * Creates a population for the given scene with a pre-existing list of genomes.
	 *
	 * @param scene      the audio scene this population will optimize
	 * @param population the initial genome list
	 */
	public AudioScenePopulation(AudioScene<?> scene, List<Genome<PackedCollection>> population) {
		this.scene = scene;
		this.pop = population;
	}

	/**
	 * Initializes the temporal cell pipeline using the specified template genome and audio output.
	 * All channels are included in the rendering pipeline.
	 *
	 * @param templateGenome the genome used to configure the scene during initialization
	 * @param output         the multi-channel audio output that receives rendered audio
	 */
	public void init(Genome<PackedCollection> templateGenome,
					 MultiChannelAudioOutput output) {
		init(templateGenome, output, null);
	}

	/**
	 * Initializes the temporal cell pipeline using the specified template genome, audio output,
	 * and an optional list of channel indices to include in rendering.
	 *
	 * @param templateGenome the genome used to configure the scene during initialization
	 * @param output         the multi-channel audio output that receives rendered audio
	 * @param channels       the channel indices to include, or {@code null} for all channels
	 */
	public void init(Genome<PackedCollection> templateGenome,
					 MultiChannelAudioOutput output, List<Integer> channels) {
		enableGenome(templateGenome);
		this.temporal = scene.runner(output, channels);
		disableGenome();
	}

	/**
	 * Returns the audio scene associated with this population.
	 *
	 * @return the managed {@link AudioScene}
	 */
	public AudioScene<?> getScene() {
		return scene;
	}

	@Override
	public List<Genome<PackedCollection>> getGenomes() { return pop; }

	/**
	 * Replaces the genome list for this population.
	 *
	 * @param pop the new list of genomes
	 */
	public void setGenomes(List<Genome<PackedCollection>> pop) { this.pop = pop; }

	@Override
	public TemporalCellular enableGenome(int index) {
		enableGenome(getGenomes().get(index));
		temporal.reset();
		return temporal;
	}

	/**
	 * Enables the given genome as the active genome for the current evaluation,
	 * verifying that no other genome is already active.
	 *
	 * @param newGenome the genome to activate
	 * @throws IllegalStateException if a genome is already active
	 */
	private void enableGenome(Genome newGenome) {
		if (currentGenome != null) {
			throw new IllegalStateException();
		}

		currentGenome = newGenome;
		scene.assignGenome((ProjectedGenome) currentGenome);
	}

	@Override
	public void disableGenome() {
		this.currentGenome = null;

		if (temporal != null) {
			temporal.reset();
		}
	}

	/**
	 * Attempts to apply the given genome to the scene to verify compatibility. Returns
	 * {@code true} if the genome is valid for the current scene configuration.
	 *
	 * @param genome the genome to validate; may be {@code null}
	 * @return {@code true} if the genome can be applied without error, {@code false} otherwise
	 */
	public boolean validateGenome(Genome genome) {
		if (genome == null) return false;

		try {
			enableGenome(genome);
			return true;
		} catch (Exception e) {
			warn("Genome incompatible with current scene (" + e.getClass().getSimpleName() + ")");
			return false;
		} finally {
			disableGenome();
		}
	}

	@Override
	public int size() { return getGenomes().size(); }

	/**
	 * Builds a {@link Runnable} that iterates over all genomes in this population, generates
	 * audio output for the specified channel and frame count, and reports results via the
	 * {@code output} consumer.
	 *
	 * @param channel      the audio channel index to generate audio for
	 * @param frames       the number of audio frames to generate per genome
	 * @param destinations supplier of output file path strings, or {@code null} for no file output
	 * @param output       consumer that receives a {@link GenerationResult} for each completed genome
	 * @return a {@link Runnable} that performs the generation when executed
	 */
	public Runnable generate(int channel, int frames, Supplier<String> destinations,
							 Consumer<GenerationResult> output) {
		return () -> {
			WaveOutput out = new WaveOutput(() ->
					Optional.ofNullable(destinations).map(s -> {
						outputPath = s.get();
						outputFile = new File(outputPath);
						return outputFile;
					}).orElse(null), 24, true);

			init(getGenomes().get(0), new MultiChannelAudioOutput(out), List.of(channel));

			OperationProfile profile = new OperationProfile("AudioScenePopulation");

			Runnable gen = null;

			for (int i = 0; i < getGenomes().size(); i++) {
				TemporalCellular cells = null;
				long start = System.currentTimeMillis();

				try {
					outputPath = null;
					cells = enableGenome(i);

					if (gen == null) {
						Supplier<Runnable> op = cells.iter(frames, false);

						if (op instanceof OperationList) {
							gen = ((OperationList) op).get(profile);
						} else {
							gen = op.get();
						}
					}

					log("Starting generation for genome " + i + " of " + getGenomes().size());
					gen.run();
				} finally {
					long generationTime = System.currentTimeMillis() - start;
					StableDurationHealthComputation.recordGenerationTime(frames, generationTime);

					out.write().get().run();

					out.reset();
					if (cells != null) cells.reset();

					disableGenome();

					if (outputPath != null)
						output.accept(new GenerationResult(outputPath, getGenomes().get(i), generationTime));
				}
			}
		};
	}


	/**
	 * Serializes all genomes in this population to the given output stream as JSON.
	 *
	 * @param s the output stream to write to
	 * @throws IOException if an I/O error occurs during serialization
	 */
	public void store(OutputStream s) throws IOException{
		store(getGenomes(), s);
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		if (temporal instanceof Destroyable) ((Destroyable) temporal).destroy();
		temporal = null;
	}

	@Override
	public Console console() { return HealthCallable.console; }

	/**
	 * Immutable record of the result produced for a single genome during an audio generation run.
	 */
	public static class GenerationResult {
		/** File system path where the generated audio was written. */
		private final String outputPath;

		/** The genome that was active when this audio was generated. */
		private final Genome<PackedCollection> genome;

		/** Wall-clock time in milliseconds taken to generate audio for this genome. */
		private final long generationTime;

		/**
		 * Creates a new generation result.
		 *
		 * @param outputPath     path to the generated audio file
		 * @param genome         the genome used during generation
		 * @param generationTime elapsed time in milliseconds for this genome's generation
		 */
		public GenerationResult(String outputPath, Genome<PackedCollection> genome, long generationTime) {
			this.outputPath = outputPath;
			this.genome = genome;
			this.generationTime = generationTime;
		}

		/**
		 * Returns the path to the generated audio output file.
		 *
		 * @return the output file path
		 */
		public String getOutputPath() {
			return outputPath;
		}

		/**
		 * Returns the genome that produced this result.
		 *
		 * @return the generating genome
		 */
		public Genome<PackedCollection> getGenome() {
			return genome;
		}

		/**
		 * Returns the wall-clock time taken to generate audio for this genome in milliseconds.
		 *
		 * @return generation time in milliseconds
		 */
		public long getGenerationTime() {
			return generationTime;
		}
	}

	/**
	 * Serializes the given list of genomes to the provided output stream as JSON.
	 *
	 * @param <G>     the gene value type
	 * @param genomes the genomes to serialize
	 * @param s       the output stream to write to
	 * @throws IOException if an I/O error occurs during serialization
	 */
	public static <G> void store(List<Genome<G>> genomes, OutputStream s) throws IOException {
		GenomeData genomeData = new GenomeData();
		for (Genome<G> genome : genomes) {
			genomeData.addGenome(genome);
		}

		ObjectMapper mapper = new ObjectMapper();
		mapper.writeValue(s, genomeData);
	}

	/**
	 * Deserializes a list of {@link Genome} instances from the given JSON input stream.
	 *
	 * @param in the input stream containing serialized genome JSON
	 * @return the deserialized list of genomes
	 * @throws IOException if an I/O error occurs during deserialization
	 */
	public static List<Genome<PackedCollection>> read(InputStream in) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		GenomeData data = mapper.readValue(in, GenomeData.class);
		return data.getGenomes();
	}

	/**
	 * JSON serialization container for genome data. Stores each genome as a {@code double[]}
	 * array of its {@link ProjectedGenome} parameter values.
	 */
	static class GenomeData extends ArrayList<double[]> implements CodeFeatures {
		/**
		 * Appends the parameter array of the given genome to this list.
		 *
		 * @param genome the genome to add; must be a {@link ProjectedGenome}
		 */
		public void addGenome(Genome genome) {
			this.add(((ProjectedGenome) genome).getParameters().toArray());
		}

		/**
		 * Converts the stored {@code double[]} arrays back into a list of {@link ProjectedGenome} instances.
		 *
		 * @return the deserialized genome list
		 */
		public List<Genome<PackedCollection>> getGenomes() {
			return stream()
					.map(params -> new ProjectedGenome(pack(params)))
					.collect(Collectors.toList());
		}
	}
}
