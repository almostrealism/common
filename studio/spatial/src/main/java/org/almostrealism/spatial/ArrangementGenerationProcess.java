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

package org.almostrealism.spatial;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.notes.SceneAudioNode;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.studio.persistence.MigrationClassLoader;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The arrangement generation process: runs {@link AudioSceneOptimizer} cycles over a
 * scene, attaches each delivered {@link AudioHealthScore} to its {@link GenomicNetwork}
 * record, and persists the records between runs. The process depends on no UI toolkit,
 * so an interactive client and a headless harness or test execute the identical flow,
 * observing score delivery, record attachment, and the persistence round trip.
 *
 * <p>Presentation concerns remain with the caller through the listener surface:
 * score attachment, per-cycle progress, completion, and errors are reported via the
 * configured callbacks, and record display names are produced by the configured
 * naming function.</p>
 */
public class ArrangementGenerationProcess implements ConsoleFeatures, Destroyable {
	/** Destination for persisted population records. */
	private final String networksFile;

	/** The population records, one per genome, carrying scores once delivered. */
	private List<GenomicNetwork> networks;

	/** The cloned scene the current run renders with. */
	private AudioScene<?> localScene;

	/** The optimizer for the current run. */
	private AudioSceneOptimizer optimizer;

	/** Number of completed optimization cycles. */
	private int iterationCount;

	/** Produces display names for records (used for their audio nodes). */
	private Function<GenomicNetwork, String> networkNaming;

	/** Receives wave detail metadata from each health evaluation, or {@code null}. */
	private Consumer<WaveDetails> waveDetailsProcessor;

	/** Notified after a delivered score is attached to its record, or {@code null}. */
	private BiConsumer<GenomicNetwork, AudioHealthScore> scoreListener;

	/** Receives evaluation errors, or {@code null} to log them here. */
	private Consumer<Exception> errorListener;

	/** Invoked at the start of each optimization cycle, or {@code null}. */
	private Runnable cycleListener;

	/** Invoked when each optimization cycle completes, or {@code null}. */
	private Runnable completionListener;

	/**
	 * Creates a process persisting population records to the given file.
	 *
	 * @param networksFile destination for persisted records
	 */
	public ArrangementGenerationProcess(String networksFile) {
		this.networksFile = networksFile;
		this.networks = new ArrayList<>();
		this.networkNaming = n -> "Network " + n.getIndex();
	}

	/** Sets the display-name function used when creating record audio nodes. */
	public void setNetworkNaming(Function<GenomicNetwork, String> networkNaming) {
		this.networkNaming = networkNaming;
	}

	/** Sets the consumer of wave detail metadata produced during health evaluation. */
	public void setWaveDetailsProcessor(Consumer<WaveDetails> waveDetailsProcessor) {
		this.waveDetailsProcessor = waveDetailsProcessor;
	}

	/** Sets the listener notified after each delivered score attaches to its record. */
	public void setScoreListener(BiConsumer<GenomicNetwork, AudioHealthScore> scoreListener) {
		this.scoreListener = scoreListener;
	}

	/** Sets the receiver of evaluation errors. */
	public void setErrorListener(Consumer<Exception> errorListener) {
		this.errorListener = errorListener;
	}

	/** Sets the callback invoked at the start of each optimization cycle. */
	public void setCycleListener(Runnable cycleListener) {
		this.cycleListener = cycleListener;
	}

	/** Sets the callback invoked when each optimization cycle completes. */
	public void setCompletionListener(Runnable completionListener) {
		this.completionListener = completionListener;
	}

	/** Returns the live list of population records. */
	public List<GenomicNetwork> getNetworks() { return networks; }

	/**
	 * Replaces the population records, for callers that manage the record list
	 * directly (trimming, reordering).
	 *
	 * @param networks the records to adopt
	 */
	public void setNetworks(List<GenomicNetwork> networks) { this.networks = networks; }

	/** Returns the optimizer for the current run, or {@code null} before {@link #prepare}. */
	public AudioSceneOptimizer getOptimizer() { return optimizer; }

	/** Returns the cloned scene for the current run, or {@code null} before {@link #prepare}. */
	public AudioScene<?> getLocalScene() { return localScene; }

	/** Returns the number of completed optimization cycles. */
	public int getIterationCount() { return iterationCount; }

	/**
	 * Persists the current records, then builds an optimizer over a clone of the
	 * given scene and wires score delivery, error, and cycle callbacks. The
	 * optimizer is available from {@link #getOptimizer()} for configuration
	 * before {@link #run()}.
	 *
	 * @param scene  the scene to optimize (cloned; the original is untouched)
	 * @param cycles the number of optimization cycles for {@link #run()}
	 * @throws IOException if the records cannot be persisted
	 */
	public void prepare(AudioScene<?> scene, int cycles) throws IOException {
		saveNetworks();

		localScene = scene.clone();
		optimizer = AudioSceneOptimizer.build(localScene, cycles);
		if (waveDetailsProcessor != null)
			optimizer.setWaveDetailsProcessor(waveDetailsProcessor);
		optimizer.setHealthListener(this::attachScore);
		if (errorListener != null) optimizer.setErrorListener(errorListener);
		if (cycleListener != null) optimizer.setCycleListener(cycleListener);
		optimizer.setCompletionListener(() -> {
			iterationCount++;
			if (completionListener != null) completionListener.run();
		});
	}

	/**
	 * Runs the prepared optimizer, then persists and reloads the records so the
	 * process ends in the same state a fresh session would load.
	 *
	 * @throws IOException if the records cannot be persisted
	 */
	public void run() throws IOException {
		optimizer.run();
		saveNetworks();
		loadNetworks();
	}

	/**
	 * Convenience for {@link #prepare} followed by {@link #run()}.
	 *
	 * @param scene  the scene to optimize
	 * @param cycles the number of optimization cycles
	 * @throws IOException if the records cannot be persisted
	 */
	public void iterate(AudioScene<?> scene, int cycles) throws IOException {
		prepare(scene, cycles);
		run();
	}

	/**
	 * Attaches a delivered health score to the record whose genome carries the
	 * given signature. Delivery is logged before anything else so a failure in
	 * attachment can never make the delivery itself invisible; records without
	 * a genome are skipped rather than treated as fatal, and a score matching
	 * no record is reported.
	 *
	 * @param signature the delivered genome signature
	 * @param score     the delivered health score
	 */
	protected void attachScore(String signature, AudioHealthScore score) {
		log("scoreStems=" + (score.getStems() == null ?
				"null" : score.getStems().size()) + " signature=" + signature);

		boolean sync = networks.stream().map(GenomicNetwork::getGenome)
				.filter(Objects::nonNull)
				.anyMatch(g -> Objects.equals(g.signature(), signature));
		if (!sync) refreshNetworks();

		GenomicNetwork matched = null;

		for (GenomicNetwork g : networks) {
			if (g.getGenome() != null &&
					Objects.equals(g.getGenome().signature(), signature)) {
				g.setHealthScore(score);

				SceneAudioNode node = new SceneAudioNode(g.getKey(),
						networkNaming.apply(g), localScene, null);
				node.setRange(0, localScene.getTotalMeasures());
				g.setSceneAudioNode(node);

				matched = g;
			}
		}

		if (matched == null) {
			warn("No record found for generated health score " + signature);
		}

		if (scoreListener != null) scoreListener.accept(matched, score);
	}

	/**
	 * Rebuilds the records from the optimizer's current population, one record
	 * per genome. Existing records (and their scores) are discarded, matching
	 * the behavior the app has always had when its records fall out of sync
	 * with the population.
	 */
	public void refreshNetworks() {
		if (optimizer == null) return;

		networks = new ArrayList<>();

		AudioScenePopulation population = (AudioScenePopulation) optimizer.getPopulation();
		for (Genome<PackedCollection> genome : population.getGenomes()) {
			networks.add(new GenomicNetwork(networks.size(), genome));
		}
	}

	/**
	 * Loads the persisted records, replacing the current list. A missing file
	 * yields an empty list; a failure to decode is reported rather than being
	 * silently indistinguishable from a fresh start.
	 */
	public void loadNetworks() {
		if (!new File(networksFile).exists()) {
			networks = new ArrayList<>();
			return;
		}

		try (FileInputStream in = new FileInputStream(networksFile);
			 InputStream migrated = MigrationClassLoader.migrateStream(in);
			 XMLDecoder dec = new XMLDecoder(migrated)) {
			networks = (List<GenomicNetwork>) dec.readObject();
			log("loadedNetworks=" + networks.size() +
					" scoredNetworks=" + networks.stream()
							.filter(g -> g.getHealthScore() != null).count() +
					" stemNetworks=" + networks.stream()
							.filter(g -> g.getHealthScore() != null &&
									g.getHealthScore().getStems() != null &&
									!g.getHealthScore().getStems().isEmpty()).count());
		} catch (Exception e) {
			warn("Unable to load population records from " + networksFile, e);
			networks = new ArrayList<>();
		}
	}

	/**
	 * Persists the current records, and mirrors their genomes into the shared
	 * population file that {@link AudioSceneOptimizer} reads its population from,
	 * so the next run evaluates the genomes these records describe.
	 *
	 * @throws IOException if the records cannot be written
	 */
	public void saveNetworks() throws IOException {
		try (FileOutputStream out = new FileOutputStream(networksFile);
			 XMLEncoder enc = new XMLEncoder(out)) {
			enc.writeObject(networks);
		}

		AudioScenePopulation.store(networks.stream()
						.map(GenomicNetwork::getGenome)
						.filter(Objects::nonNull)
						.collect(Collectors.toList()),
				new FileOutputStream(AudioSceneOptimizer.POPULATION_FILE));
	}

	@Override
	public void destroy() {
		if (optimizer != null) {
			optimizer.destroy();
			optimizer = null;
		}
		if (localScene != null) {
			localScene.destroy();
			localScene = null;
		}
	}
}
