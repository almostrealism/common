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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.audio.health.StableDurationHealthComputation;
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

public class AudioPopulationOptimizer<O extends Temporal> extends
		PopulationOptimizer<PackedCollection, PackedCollection, O, AudioHealthScore>
		implements Runnable, Destroyable {
	public static String outputDir = SystemUtils.getProperty("AR_AUDIO_OUTPUT", SystemUtils.getLocalDestination("health"));

	public static final boolean enableWavOutput = true;
	public static boolean enableStemOutput = true;
	public static boolean enableIsolatedContext = false;
	public static boolean enableExplicitGc = false;

	private final String file;
	private int tot;
	private final AtomicInteger count;

	private String outputPrefix;

	private Runnable cycleListener;
	private Runnable completionListener;

	public AudioPopulationOptimizer(int stemCount, Function<List<Genome<PackedCollection>>, Population> children,
									Supplier<GenomeBreeder<PackedCollection>> breeder,
									Supplier<Supplier<Genome<PackedCollection>>> generator,
									String file, int iterationsPerRun) {
		this(() -> healthComputation(stemCount), children, breeder, generator, file, iterationsPerRun);
	}

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

	public void setIterationsPerRun(int tot) { this.tot = tot; }

	public void setCycleListener(Runnable r) {
		this.cycleListener = r;
	}

	public void setCompletionListener(Runnable r) {
		this.completionListener = r;
	}

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

	public void storePopulation() {
		try {
			((AudioScenePopulation) getPopulation()).store(new FileOutputStream(file));
			log("Wrote " + file);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	protected String generatePrefix() {
		return DigestUtils.md5Hex(String.valueOf(System.currentTimeMillis()));
	}

	@Override
	public void destroy() {
		resetHealth();
	}

	public static <O extends Temporal> HealthComputation<O, AudioHealthScore> healthComputation(int channels) {
		return (HealthComputation<O, AudioHealthScore>) new StableDurationHealthComputation(channels, true);
	}
}
