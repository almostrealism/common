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

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Generated;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.GenomeBreeder;
import org.almostrealism.io.Console;

import org.almostrealism.time.Temporal;
import org.almostrealism.CodeFeatures;

/**
 * Evolutionary optimizer that evolves a population of genomes through fitness-based selection and breeding.
 * <p>
 * {@code PopulationOptimizer} implements a genetic algorithm framework for evolving populations
 * of genomes. Each generation, organisms are evaluated for fitness, sorted by score, and the
 * best performers are bred to create offspring for the next generation.
 * </p>
 *
 * <h2>Evolutionary Process</h2>
 * <p>
 * The evolutionary loop consists of:
 * </p>
 * <ol>
 *   <li>Fitness evaluation - All genomes are evaluated using {@link HealthComputation}</li>
 *   <li>Selection - Genomes are sorted by fitness score</li>
 *   <li>Breeding - Top performers are combined using {@link GenomeBreeder}</li>
 *   <li>Population refresh - Low performers are replaced with fresh random genomes</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <p>
 * Key static configuration parameters:
 * </p>
 * <ul>
 *   <li>{@link #popSize} - Target population size (default: 100)</li>
 *   <li>{@link #maxChildren} - Maximum offspring per generation (default: 110% of popSize)</li>
 *   <li>{@link #THREADS} - Parallel fitness evaluation threads (default: 1)</li>
 *   <li>{@link #enableBreeding} - Whether to breed genomes (default: true)</li>
 *   <li>{@link #lowestHealth} - Minimum fitness threshold for survival (default: 0.0)</li>
 * </ul>
 *
 * <h2>Offspring Potential</h2>
 * <p>
 * Each parent pair has probabilistic chances of producing multiple offspring:
 * </p>
 * <ul>
 *   <li>First offspring - Always produced</li>
 *   <li>Second offspring - Probability: {@link #secondaryOffspringPotential} (default: 25%)</li>
 *   <li>Third offspring - Probability: {@link #tertiaryOffspringPotential} (default: 25%)</li>
 *   <li>Fourth offspring - Probability: {@link #quaternaryOffspringPotential} (default: 25%)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create optimizer with required components
 * PopulationOptimizer<Gene, Organism, NeuralNetwork, HealthScore> optimizer =
 *     new PopulationOptimizer<>(
 *         () -> new AccuracyHealthComputation(),  // Fitness evaluator
 *         genomes -> new NetworkPopulation(genomes),  // Population constructor
 *         () -> new CrossoverBreeder(),           // Breeding strategy
 *         () -> () -> randomGenome()              // Random genome generator
 *     );
 *
 * // Configure
 * PopulationOptimizer.popSize = 200;
 * PopulationOptimizer.THREADS = 8;
 *
 * // Set initial population
 * optimizer.setPopulation(initialPopulation);
 *
 * // Set listeners
 * optimizer.setHealthListener((signature, score) ->
 *     log("Genome " + signature + ": " + score.getScore())
 * );
 *
 * // Run evolution for multiple generations
 * for (int gen = 0; gen < 50; gen++) {
 *     optimizer.iterate();
 *     log("Generation " + gen +
 *         ": avg=" + optimizer.getAverageScore() +
 *         ", max=" + optimizer.getMaxScore());
 * }
 * }</pre>
 *
 * @param <G> the gene type stored in genomes
 * @param <T> unused type parameter (for compatibility)
 * @param <O> the organism type produced when genomes are enabled
 * @param <S> the health score type returned by fitness evaluations
 *
 * @see Population
 * @see HealthComputation
 * @see GenomeBreeder
 * @see Genome
 *
 * @author Michael Murray
 */
public class PopulationOptimizer<G, T, O extends Temporal, S extends HealthScore> implements Generated<Supplier<Genome<G>>, PopulationOptimizer>, CodeFeatures {
	/** Number of parallel threads for fitness evaluation (default: 1). */
	public static int THREADS = 1;

	/** Enables verbose logging of evolutionary progress. */
	public static boolean enableVerbose = false;

	/** Enables detailed genome display in logs. */
	public static boolean enableDisplayGenomes = false;

	/** Enables breeding between genomes; set to false to skip breeding phase. */
	public static boolean enableBreeding = true;

	/** Optional target genome index for focused evaluation (testing purposes). */
	public static OptionalInt targetGenome = OptionalInt.empty();

	/** Target population size (default: 100). */
	public static int popSize = 100;

	/** Maximum number of offspring per generation (default: 110% of popSize). */
	public static int maxChildren = (int) (popSize * 1.10);

	/** Probability of producing a second offspring from a parent pair (default: 0.25). */
	public static double secondaryOffspringPotential = 0.25;

	/** Probability of producing a third offspring from a parent pair (default: 0.25). */
	public static double tertiaryOffspringPotential = 0.25;

	/** Probability of producing a fourth offspring from a parent pair (default: 0.25). */
	public static double quaternaryOffspringPotential = 0.25;

	/** Minimum fitness score required for a genome to survive to the next generation (default: 0.0). */
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

	/**
	 * Creates a population optimizer without an initial population.
	 * <p>
	 * Use {@link #setPopulation(Population)} to set the initial population before calling
	 * {@link #iterate()}.
	 * </p>
	 *
	 * @param h         supplier for health computation instances
	 * @param children  function to create a new population from a list of genomes
	 * @param breeder   supplier for genome breeder instances
	 * @param generator supplier for random genome generators
	 */
	public PopulationOptimizer(Supplier<HealthComputation<O, S>> h,
							   Function<List<Genome<G>>, Population> children,
							   Supplier<GenomeBreeder<G>> breeder, Supplier<Supplier<Genome<G>>> generator) {
		this(null, h, children, breeder, generator);
	}

	/**
	 * Creates a population optimizer with an initial population.
	 *
	 * @param p         the initial population; may be null (set later via {@link #setPopulation(Population)})
	 * @param h         supplier for health computation instances
	 * @param children  function to create a new population from a list of genomes
	 * @param breeder   supplier for genome breeder instances
	 * @param generator supplier for random genome generators
	 */
	public PopulationOptimizer(Population<G, O> p, Supplier<HealthComputation<O, S>> h,
							   Function<List<Genome<G>>, Population> children,
							   Supplier<GenomeBreeder<G>> breeder, Supplier<Supplier<Genome<G>>> generator) {
		this.population = p;
		this.healthSupplier = h;
		this.children = children;
		this.breeder = breeder;
		this.generatorSupplier = generator;
	}

	/**
	 * Sets the population to be evolved.
	 *
	 * @param population the population of genomes
	 */
	public void setPopulation(Population<G, O> population) { this.population = population; }

	/**
	 * Returns the current population.
	 *
	 * @return the population of genomes
	 */
	public Population<G, O> getPopulation() { return this.population; }

	/**
	 * Resets the health computation, releasing any associated resources.
	 * <p>
	 * This should be called when changing fitness evaluation strategies
	 * or when resources need to be freed.
	 * </p>
	 */
	public void resetHealth() {
		if (health instanceof Destroyable) {
			((Destroyable) health).destroy();
		}

		health = null;
	}

	/**
	 * Returns the health computation instance, creating one if necessary.
	 *
	 * @return the health computation used for fitness evaluation
	 */
	public HealthComputation<?, ?> getHealthComputation() {
		if (health == null) health = healthSupplier.get();
		return health;
	}

	/**
	 * Sets the function used to create new populations from genome lists.
	 *
	 * @param pop the population construction function
	 */
	public void setChildrenFunction(Function<List<Genome<G>>, Population> pop) { this.children = pop; }

	/**
	 * Returns the function used to create new populations from genome lists.
	 *
	 * @return the population construction function
	 */
	public Function<List<Genome<G>>, Population> getChildrenFunction() { return children; }

	/**
	 * Returns the health listener called after each fitness evaluation.
	 *
	 * @return the health listener, or null if not set
	 */
	public BiConsumer<String, S> getHealthListener() { return healthListener; }

	/**
	 * Sets a listener to be notified after each genome's fitness is evaluated.
	 * <p>
	 * The listener receives the genome's signature string and its health score.
	 * </p>
	 *
	 * @param healthListener callback for fitness evaluation results
	 */
	public void setHealthListener(BiConsumer<String, S> healthListener) { this.healthListener = healthListener; }

	/**
	 * Returns the error listener for handling evaluation failures.
	 *
	 * @return the error listener, or null if not set
	 */
	public Consumer<Exception> getErrorListener() { return errorListener; }

	/**
	 * Sets the error listener for handling fitness evaluation failures.
	 *
	 * @param errorListener callback for handling exceptions
	 */
	public void setErrorListener(Consumer<Exception> errorListener) { this.errorListener = errorListener; }

	/**
	 * Resets the random genome generator.
	 * <p>
	 * A new generator will be obtained from the supplier on next use.
	 * </p>
	 */
	public void resetGenerator() {
		generator = null;
	}

	/**
	 * Returns the random genome generator, creating one if necessary.
	 *
	 * @return the genome generator
	 */
	@Override
	public Supplier<Genome<G>> getGenerator() {
		if (generator == null && generatorSupplier != null)
			generator = generatorSupplier.get();
		return generator;
	}

	/**
	 * Returns the average fitness score from the last generation.
	 *
	 * @return the average fitness score, or 0.0 if no evaluation has occurred
	 */
	public double getAverageScore() { return scoring == null ? 0.0 : scoring.getAverageScore(); }

	/**
	 * Returns the maximum fitness score from the last generation.
	 *
	 * @return the maximum fitness score, or 0.0 if no evaluation has occurred
	 */
	public double getMaxScore() { return scoring == null ? 0.0 : scoring.getMaxScore(); }

	/**
	 * Performs one evolutionary iteration (generation).
	 * <p>
	 * This method executes the complete evolutionary cycle:
	 * </p>
	 * <ol>
	 *   <li>If breeding is enabled, creates offspring from current population</li>
	 *   <li>Fills remaining population slots with fresh random genomes</li>
	 *   <li>Evaluates fitness of all genomes</li>
	 *   <li>Sorts population by fitness score</li>
	 * </ol>
	 * <p>
	 * After iteration completes, statistics are available via {@link #getAverageScore()}
	 * and {@link #getMaxScore()}.
	 * </p>
	 */
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
			Genome g2 = sorted.isEmpty() ? null : itr.next();

			w:
			for (int i = 0; itr.hasNext(); i++) {
				g1 = g2;
				g2 = itr.next();
				if (genomes.size() >= maxChildren) break w;

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

	/**
	 * Called after breeding phase completes.
	 * <p>
	 * Subclasses can override this method to perform additional processing
	 * after the breeding phase but before fitness evaluation.
	 * </p>
	 */
	public void breedingComplete() { }

	/**
	 * Creates an offspring from two parent genomes and adds it to the genome list.
	 * <p>
	 * The offspring is created using the configured {@link GenomeBreeder}. Duplicate
	 * genomes (matching signature) are not added to prevent clones in the population.
	 * </p>
	 *
	 * @param genomes the list to add the offspring to
	 * @param g1      the first parent genome
	 * @param g2      the second parent genome
	 */
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

	/**
	 * Formats a decimal value as a percentage string.
	 *
	 * @param d the decimal value to format (e.g., 0.85 for 85%)
	 * @return the formatted percentage string (e.g., "85.00%")
	 */
	public static String percent(double d) {
		int cents = (int) (d * 100);
		int decimal = (int) (((d * 100) - cents) * 100);
		if (decimal < 0) decimal = -decimal;
		String decimalString = String.valueOf(decimal);
		if (decimalString.length() < 2) decimalString = "0" + decimalString;
		return cents + "." + decimalString + "%";
	}
}
