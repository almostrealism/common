/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.optimize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import io.almostrealism.relation.Generated;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.GenomeBreeder;
import org.almostrealism.io.Console;

import org.almostrealism.time.Temporal;
import org.almostrealism.CodeFeatures;

public class PopulationOptimizer<G, T, O extends Temporal, S extends HealthScore> implements Generated<Supplier<Genome<G>>, PopulationOptimizer>, CodeFeatures {
	public static int THREADS = 1;

	public static boolean enableVerbose = false;
	public static boolean enableDisplayGenomes = false;
	public static boolean enableBreeding = true;

	public static OptionalInt targetGenome = OptionalInt.empty();

	public static int popSize = 100;
	public static int maxChildren = (int) (popSize * 1.10);
	public static double secondaryOffspringPotential = 0.25;
	public static double tertiaryOffspringPotential = 0.25;
	public static double quaternaryOffspringPotential = 0.25;
	public static double lowestHealth = 0.0;

	private Population<G, O> population;
	private Function<List<Genome<G>>, Population> children;

	private Supplier<Supplier<Genome<G>>> generatorSupplier;
	private Supplier<Genome<G>> generator;

	private Supplier<HealthComputation<O, S>> healthSupplier;
	private HealthComputation<O, S> health;

	private Supplier<GenomeBreeder<G>> breeder;

	private BiConsumer<String, S> healthListener;
	private Consumer<Exception> errorListener;
	private HealthScoring scoring;

	public PopulationOptimizer(Supplier<HealthComputation<O, S>> h,
							   Function<List<Genome<G>>, Population> children,
							   Supplier<GenomeBreeder<G>> breeder, Supplier<Supplier<Genome<G>>> generator) {
		this(null, h, children, breeder, generator);
	}

	public PopulationOptimizer(Population<G, O> p, Supplier<HealthComputation<O, S>> h,
							   Function<List<Genome<G>>, Population> children,
							   Supplier<GenomeBreeder<G>> breeder, Supplier<Supplier<Genome<G>>> generator) {
		this.population = p;
		this.healthSupplier = h;
		this.children = children;
		this.breeder = breeder;
		this.generatorSupplier = generator;
	}

	public void setPopulation(Population<G, O> population) { this.population = population; }

	public Population<G, O> getPopulation() { return this.population; }

	public void resetHealth() {
		health = null;
	}

	public HealthComputation<?, ?> getHealthComputation() {
		if (health == null) health = healthSupplier.get();
		return health;
	}

	public void setChildrenFunction(Function<List<Genome<G>>, Population> pop) { this.children = pop; }

	public Function<List<Genome<G>>, Population> getChildrenFunction() { return children; }

	public BiConsumer<String, S> getHealthListener() { return healthListener; }

	public void setHealthListener(BiConsumer<String, S> healthListener) { this.healthListener = healthListener; }

	public Consumer<Exception> getErrorListener() { return errorListener; }
	public void setErrorListener(Consumer<Exception> errorListener) { this.errorListener = errorListener; }
	
	public void resetGenerator() {
		generator = null;
	}

	@Override
	public Supplier<Genome<G>> getGenerator() {
		if (generator == null && generatorSupplier != null)
			generator = generatorSupplier.get();
		return generator;
	}

	public double getAverageScore() { return scoring == null ? 0.0 : scoring.getAverageScore(); }

	public double getMaxScore() { return scoring == null ? 0.0 : scoring.getMaxScore(); }

	public void iterate() {
		long start = System.currentTimeMillis();

		// Sort the population
		List<Genome<G>> sorted = population.getGenomes();

		if (enableBreeding) {
			// Fresh genetic material
			List<Genome<G>> genomes = new ArrayList<>();

			// Mate in order of health
			Iterator<Genome<G>> itr = sorted.iterator();

			Genome g1;
			Genome g2 = itr.next();

			w:
			for (int i = 0; itr.hasNext(); i++) {
				g1 = g2;
				if (genomes.size() >= maxChildren || itr.hasNext() == false) break w;
				g2 = itr.next();

				// Combine chromosomes to produce new offspring
				breed(genomes, g1, g2);

				if (StrictMath.random() < secondaryOffspringPotential && genomes.size() < maxChildren) {
					// Combine chromosomes to produce a second offspring
					breed(genomes, g1, g2);
				} else {
					continue w;
				}

				if (StrictMath.random() < tertiaryOffspringPotential && genomes.size() < maxChildren) {
					// Combine chromosomes to produce a third offspring
					breed(genomes, g1, g2);
				} else {
					continue w;
				}

				if (StrictMath.random() < quaternaryOffspringPotential && genomes.size() < maxChildren) {
					// Combine chromosomes to produce a fourth offspring
					breed(genomes, g1, g2);
				} else {
					continue w;
				}
			}

			int add = popSize - genomes.size();

			console().println("Generating new population with " + genomes.size() + " children");

			this.population.getGenomes().clear();
			this.population.getGenomes().addAll(genomes);

			if (generator != null && add > 0) {
				log("Adding an additional " + add + " members");

				IntStream.range(0, add)
						.mapToObj(i -> generator.get())
						.forEach(this.population.getGenomes()::add);
			}

			breedingComplete();

			long sec = (System.currentTimeMillis() - start) / 1000;
			if (enableVerbose)
				log("Breeding completed after " + sec + " seconds");
		}

		// Sort the population
		orderByHealth(population);
	}

	public void breedingComplete() { }

	public void breed(List<Genome<G>> genomes, Genome g1, Genome g2) {
		Genome<G> g = breeder.get().combine(g1, g2);
		String sig = g.signature();

		for (int i = 0; i < genomes.size(); i++) {
			if (Objects.equals(genomes.get(i).signature(), sig))
				return;
		}

		genomes.add(g);
	}

	private synchronized void orderByHealth(Population<G, O> pop) {
		if (THREADS > 1) throw new UnsupportedOperationException();

		ExecutorService s = Executors.newFixedThreadPool(THREADS);
		ExecutorCompletionService<S> executor = new ExecutorCompletionService<>(s);

		try {
			final HashMap<Genome, Double> healthTable = new HashMap<>();

			scoring = new HealthScoring(pop.size());

			console().print("Calculating health");
			if (enableVerbose) {
				console().println("...");
			} else {
				console().print(".");
			}

			int count = pop.size();

			for (int i = 0; i < count; i++) {
				int fi = i;

				HealthCallable<O, S> call = new HealthCallable<>(() -> pop.enableGenome(targetGenome.orElse(fi)), health, scoring, h -> {
					healthTable.put(pop.getGenomes().get(targetGenome.orElse(fi)), h.getScore());

					if (healthListener != null)
						healthListener.accept(pop.getGenomes().get(targetGenome.orElse(fi)).signature(), h);

					if (enableVerbose) {
						console().println();
						console().println("Health of Network " + fi + " is " + percent(h.getScore()));
					} else {
						console().print(".");
					}
				}, pop::disableGenome);
				call.setHeap(Heap.getDefault());
				call.setErrorListener(errorListener);

				executor.submit(call);
			}

			for (int i = 0; i < count; i++) {
				try {
					executor.take().get();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (ExecutionException e) {
					if (e.getCause() instanceof RuntimeException) {
						throw (RuntimeException) e.getCause();
					} else if (e.getCause() != null) {
						throw new RuntimeException(e.getCause());
					} else {
						throw new RuntimeException(e);
					}
				}
			}

			if (!enableVerbose) console().println();

			console().println("Average health for this round is " +
					percent(scoring.getAverageScore()) + ", max " + percent(scoring.getMaxScore()));
			TreeSet<Genome<G>> sorted = new TreeSet<>((g1, g2) -> {
				double h1 = healthTable.get(g1);
				double h2 = healthTable.get(g2);

				int i = (int) ((h2 - h1) * 10000000);

				if (i == 0) {
					if (h1 > h2) {
						return -1;
					} else {
						return 1;
					}
				}

				return i;
			});

			for (int i = 0; i < pop.size(); i++) {
				Genome g = pop.getGenomes().get(i);
				if (healthTable.get(g) >= lowestHealth) sorted.add(g);
			}

			pop.getGenomes().clear();
			pop.getGenomes().addAll(sorted);
		} finally {
			s.shutdown();
		}
	}

	@Override
	public Console console() { return HealthCallable.console; }

	public static String percent(double d) {
		int cents = (int) (d * 100);
		int decimal = (int) (((d * 100) - cents) * 100);
		if (decimal < 0) decimal = -decimal;
		String decimalString = String.valueOf(decimal);
		if (decimalString.length() < 2) decimalString = "0" + decimalString;
		return cents + "." + decimalString + "%";
	}
}
