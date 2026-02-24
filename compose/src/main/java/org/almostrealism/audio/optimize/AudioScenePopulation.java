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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.health.StableDurationHealthComputation;
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

public class AudioScenePopulation implements Population<PackedCollection, TemporalCellular>, Destroyable, CodeFeatures {

	private final AudioScene<?> scene;

	private List<Genome<PackedCollection>> pop;
	private Genome currentGenome;
	private TemporalCellular temporal;

	private String outputPath;
	private File outputFile;

	public AudioScenePopulation(AudioScene<?> scene) {
		this(scene, new ArrayList<>());
	}

	public AudioScenePopulation(AudioScene<?> scene, List<Genome<PackedCollection>> population) {
		this.scene = scene;
		this.pop = population;
	}

	public void init(Genome<PackedCollection> templateGenome,
					 MultiChannelAudioOutput output) {
		init(templateGenome, output, null);
	}

	public void init(Genome<PackedCollection> templateGenome,
					 MultiChannelAudioOutput output, List<Integer> channels) {
		enableGenome(templateGenome);
		this.temporal = scene.runner(output, channels);
		disableGenome();
	}

	public AudioScene<?> getScene() {
		return scene;
	}

	@Override
	public List<Genome<PackedCollection>> getGenomes() { return pop; }
	public void setGenomes(List<Genome<PackedCollection>> pop) { this.pop = pop; }

	@Override
	public TemporalCellular enableGenome(int index) {
		enableGenome(getGenomes().get(index));
		temporal.reset();
		return temporal;
	}

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

	public static class GenerationResult {
		private final String outputPath;
		private final Genome<PackedCollection> genome;
		private final long generationTime;

		public GenerationResult(String outputPath, Genome<PackedCollection> genome, long generationTime) {
			this.outputPath = outputPath;
			this.genome = genome;
			this.generationTime = generationTime;
		}

		public String getOutputPath() {
			return outputPath;
		}

		public Genome<PackedCollection> getGenome() {
			return genome;
		}

		public long getGenerationTime() {
			return generationTime;
		}
	}

	public static <G> void store(List<Genome<G>> genomes, OutputStream s) throws IOException {
		GenomeData genomeData = new GenomeData();
		for (Genome<G> genome : genomes) {
			genomeData.addGenome(genome);
		}

		ObjectMapper mapper = new ObjectMapper();
		mapper.writeValue(s, genomeData);
	}

	public static List<Genome<PackedCollection>> read(InputStream in) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		GenomeData data = mapper.readValue(in, GenomeData.class);
		return data.getGenomes();
	}

	static class GenomeData extends ArrayList<double[]> implements CodeFeatures {
		// This class is used to serialize the genome data as a list of double arrays
		public void addGenome(Genome genome) {
			this.add(((ProjectedGenome) genome).getParameters().toArray());
		}

		public List<Genome<PackedCollection>> getGenomes() {
			return stream()
					.map(params -> new ProjectedGenome(pack(params)))
					.collect(Collectors.toList());
		}
	}
}
