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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.GenomeBreeder;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.optimize.HealthComputation;
import org.almostrealism.optimize.Population;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.time.Temporal;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Population-based optimizer for audio scene parameters. Extends {@link PopulationOptimizer}
 * to drive iterative evolutionary optimization cycles that produce WAV audio output evaluated
 * by an {@link AudioHealthScore} health metric.
 *
 * <p>Each optimization run reads the current population from a JSON file, executes one or more
 * breeding and health-evaluation cycles, and writes the updated population back to disk. Optionally
 * WAV and stem files are written alongside each health computation for offline analysis.</p>
 *
 * @param <O> the temporal type evaluated during each optimization cycle
 */
public class AudioPopulationOptimizer<O extends Temporal> extends
		PopulationOptimizer<PackedCollection, PackedCollection, O, AudioHealthScore>
		implements Runnable, Destroyable {
	/** Directory where generated WAV health-check files are written. Defaults to the local {@code health/} destination. */
	public static String outputDir = SystemUtils.getProperty("AR_AUDIO_OUTPUT", SystemUtils.getLocalDestination("health"));

	/** When {@code true}, a WAV file is generated for each health-check iteration. */
	public static final boolean enableWavOutput = true;

	/** When {@code true}, per-channel stem WAV files are also written during health checks. */
	public static boolean enableStemOutput = true;

	/** When {@code true}, each optimization cycle runs inside an isolated data context. */
	public static boolean enableIsolatedContext = false;

	/** When {@code true}, {@link System#gc()} is called explicitly after each cycle. */
	public static boolean enableExplicitGc = false;

	/** Path to the JSON file used for population persistence. */
	private final String file;

	/** Total number of optimization iterations to execute per {@link #run()} invocation. */
	private int tot;

	/** Monotonically increasing counter used to uniquely name generated output files. */
	private final AtomicInteger count;

	/** MD5-based prefix string that groups output files from the same breeding generation. */
	private String outputPrefix;

	/** Optional callback invoked at the start of each optimization cycle. */
	private Runnable cycleListener;

	/** Optional callback invoked after each optimization cycle completes. */
	private Runnable completionListener;

	/**
	 * Constructs an optimizer using the default {@link StableDurationHealthComputation}
	 * configured for the given number of stem channels.
	 *
	 * @param stemCount        the number of audio channels evaluated by the health computation
	 * @param children         function that builds a {@link Population} from a list of genomes
	 * @param breeder          supplier of the {@link GenomeBreeder} used to produce offspring
	 * @param generator        supplier of a genome supplier for generating new individuals
	 * @param file             path to the JSON population persistence file
	 * @param iterationsPerRun the number of optimization cycles to execute per {@link #run()} call
	 */
	public AudioPopulationOptimizer(int stemCount, Function<List<Genome<PackedCollection>>, Population> children,
									Supplier<GenomeBreeder<PackedCollection>> breeder,
									Supplier<Supplier<Genome<PackedCollection>>> generator,
									String file, int iterationsPerRun) {
		this(() -> healthComputation(stemCount), children, breeder, generator, file, iterationsPerRun);
	}

	/**
	 * Constructs an optimizer with an explicitly supplied health computation.
	 *
	 * @param health           supplier of the health computation used to evaluate each individual
	 * @param children         function that builds a {@link Population} from a list of genomes
	 * @param breeder          supplier of the {@link GenomeBreeder} used to produce offspring
	 * @param generator        supplier of a genome supplier for generating new individuals
	 * @param file             path to the JSON population persistence file
	 * @param iterationsPerRun the number of optimization cycles to execute per {@link #run()} call
	 */
	public AudioPopulationOptimizer(Supplier<HealthComputation<O, AudioHealthScore>> health,
									Function<List<Genome<PackedCollection>>, Population> children,
									Supplier<GenomeBreeder<PackedCollection>> breeder,
									Supplier<Supplier<Genome<PackedCollection>>> generator,
									String file, int iterationsPerRun) {
		super(health, children, breeder, generator);
		this.file = file;
		this.tot = iterationsPerRun;
		this.count = new AtomicInteger();
	}

	/**
	 * Initializes output file suppliers on the health computation so that each iteration
	 * writes to a uniquely named WAV file. When stem output is enabled, per-stem file
	 * suppliers are also configured.
	 */
	public void init() {
		if (enableWavOutput) {
			File d = new File(outputDir);
			if (!d.exists()) d.mkdir();

			((StableDurationHealthComputation) getHealthComputation()).setOutputFile(() ->
					outputDir + "/" + outputPrefix + "-" + count.incrementAndGet() + ".wav");

			if (enableStemOutput) {
				((StableDurationHealthComputation) getHealthComputation()).setStemFile(i ->
						outputDir + "/" + outputPrefix + "-" + count.get() + "." + i + ".wav");
			}
		}
	}

	/**
	 * Sets the number of optimization iterations to execute per {@link #run()} call.
	 *
	 * @param tot the new iteration count
	 */
	public void setIterationsPerRun(int tot) { this.tot = tot; }

	/**
	 * Registers a callback that is invoked at the start of each optimization cycle.
	 *
	 * @param r the cycle listener runnable
	 */
	public void setCycleListener(Runnable r) {
		this.cycleListener = r;
	}

	/**
	 * Registers a callback that is invoked after each optimization cycle completes.
	 *
	 * @param r the completion listener runnable
	 */
	public void setCompletionListener(Runnable r) {
		this.completionListener = r;
	}

	/**
	 * Reads genome data from the population persistence file and sets the current population.
	 * If the file does not exist an empty population is used instead.
	 *
	 * @throws FileNotFoundException if the persistence file path is invalid
	 */
	public void readPopulation() throws FileNotFoundException {
		List<Genome<PackedCollection>> loaded;

		if (new File(file).exists()) {
			try {
				loaded = AudioScenePopulation.read(new FileInputStream(file));
				log("Read chromosome data from " + file);
			} catch (IOException e) {
				e.printStackTrace();
				loaded = new ArrayList<>();
			}
		} else {
			loaded = new ArrayList<>();
		}

		setPopulation(getChildrenFunction().apply(loaded));
		storePopulation();
		log(getPopulation().size() + " networks in population");
	}

	@Override
	public void run() {
		outputPrefix = generatePrefix();

		for (int i = 0; i < tot; i++) {
			if (cycleListener != null) cycleListener.run();

			Callable<Void> c = () -> {
				init();
				readPopulation();
				iterate();
				storePopulation();
				return null;
			};

			if (enableIsolatedContext) {
				dc(c);
			} else {
				try {
					c.call();
				} catch (RuntimeException e) {
					throw e;
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			if (enableIsolatedContext) {
				resetHealth();
				resetGenerator();
			}

			if (completionListener != null) completionListener.run();
			if (enableExplicitGc) System.gc();
		}
	}

	@Override
	public void breedingComplete() {
		storePopulation();
		count.set(0);
		outputPrefix = generatePrefix();
	}

	/**
	 * Serializes the current population to the configured JSON persistence file.
	 */
	public void storePopulation() {
		try {
			((AudioScenePopulation) getPopulation()).store(new FileOutputStream(file));
			log("Wrote " + file);
		} catch (IOException e) {
			System.err.println("AudioPopulationOptimizer: " + e.getMessage());
		}
	}

	/**
	 * Generates a unique MD5-based prefix string derived from the current time, used to
	 * group output WAV files produced within the same breeding generation.
	 *
	 * @return an MD5 hex string derived from the current system time
	 */
	protected String generatePrefix() {
		return DigestUtils.md5Hex(String.valueOf(System.currentTimeMillis()));
	}

	@Override
	public void destroy() {
		resetHealth();
	}

	/**
	 * Creates a default {@link HealthComputation} backed by a {@link StableDurationHealthComputation}
	 * configured to evaluate the given number of audio channels.
	 *
	 * @param <O>      the temporal type to evaluate
	 * @param channels the number of audio channels to assess
	 * @return a health computation suitable for use with this optimizer
	 */
	public static <O extends Temporal> HealthComputation<O, AudioHealthScore> healthComputation(int channels) {
		return (HealthComputation<O, AudioHealthScore>) new StableDurationHealthComputation(channels, true);
	}
}
