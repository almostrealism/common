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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.GenomeBreeder;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.optimize.HealthComputation;
import org.almostrealism.optimize.HealthScore;
import org.almostrealism.optimize.Population;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Exercises {@link PopulationOptimizer} elitism directly, with a lightweight
 * in-memory population and a deterministic health computation, so the carry-over
 * guarantee is verified without rendering audio.
 *
 * <p>Each genome's fitness is its first parameter, so a genome built with a high
 * first parameter is a high scorer. Children bred here always score {@code 0}, so
 * after a generation is re-evaluated the carried elites rank above every child and
 * the test can assert both their survival (by genome identity) and that the best
 * score is retained.</p>
 *
 * <p>This test lives in the compose module rather than beside
 * {@link PopulationOptimizer} because the optimize module declares no test
 * dependencies (no JUnit, no {@link TestSuiteBase}); compose is the nearest module
 * that both provides the test harness and depends on the optimizer.</p>
 */
public class PopulationOptimizerElitismTest extends TestSuiteBase {

	/** A trivial organism carrying the fitness its genome encodes. */
	private static class ScoredOrganism implements Temporal {
		/** The fitness this organism reports. */
		private final double score;

		/**
		 * Creates an organism reporting the given fitness.
		 *
		 * @param score the fitness
		 */
		private ScoredOrganism(double score) { this.score = score; }

		/**
		 * {@inheritDoc}
		 *
		 * <p>Does nothing: this organism is only a fitness carrier.</p>
		 */
		@Override
		public Supplier<Runnable> tick() { return () -> () -> { }; }
	}

	/** Health computation that reports the target organism's carried fitness. */
	private static class ScoredHealth implements HealthComputation<ScoredOrganism, HealthScore> {
		/** The organism currently being evaluated. */
		private ScoredOrganism target;

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void setTarget(ScoredOrganism target) { this.target = target; }

		/**
		 * {@inheritDoc}
		 *
		 * <p>Returns the target organism's carried fitness.</p>
		 */
		@Override
		public HealthScore computeHealth() {
			double value = target.score;
			return () -> value;
		}
	}

	/** Population whose genomes each carry their fitness as their first parameter. */
	private static class ScoredPopulation implements Population<PackedCollection, ScoredOrganism> {
		/** The genomes of this population, mutated in place by the optimizer. */
		private final List<Genome<PackedCollection>> genomes;

		/**
		 * Creates a population over the given genomes.
		 *
		 * @param genomes the genomes; must be mutable
		 */
		private ScoredPopulation(List<Genome<PackedCollection>> genomes) {
			this.genomes = genomes;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public List<Genome<PackedCollection>> getGenomes() { return genomes; }

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int size() { return genomes.size(); }

		/**
		 * {@inheritDoc}
		 *
		 * <p>Builds an organism carrying the genome's fitness.</p>
		 */
		@Override
		public ScoredOrganism enableGenome(int index) {
			return new ScoredOrganism(scoreOf(genomes.get(index)));
		}

		/**
		 * {@inheritDoc}
		 *
		 * <p>Does nothing: no resources are held per genome.</p>
		 */
		@Override
		public void disableGenome() { }
	}

	/**
	 * Returns a genome's fitness: its first parameter.
	 *
	 * @param genome the genome to score
	 * @return the first parameter of the genome
	 */
	private static double scoreOf(Genome<PackedCollection> genome) {
		return ((ProjectedGenome) genome).getParameters().toDouble(0);
	}

	/**
	 * Builds a three-parameter genome. The first parameter is the genome's fitness
	 * (see {@link #scoreOf}); the others distinguish otherwise-equal genomes.
	 *
	 * @param first  the first parameter, which is the genome's fitness
	 * @param second the second parameter
	 * @param third  the third parameter
	 * @return a genome carrying those parameters
	 */
	private static ProjectedGenome genome(double first, double second, double third) {
		return new ProjectedGenome(PackedCollection.of(first, second, third));
	}

	/**
	 * Builds an optimizer over the given genomes whose children always score zero
	 * (so they never displace a carried elite once re-evaluated) and whose fresh
	 * genomes are likewise zero-scoring but distinct. The organism and health
	 * computation are initialized so {@link PopulationOptimizer#iterate()} can
	 * evaluate the population.
	 *
	 * @param eliteCount the elite count to configure
	 * @param genomes    the initial population, best scorer first
	 * @return the configured optimizer, population already set
	 */
	private PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore>
			optimizer(int eliteCount, List<Genome<PackedCollection>> genomes) {
		AtomicInteger unique = new AtomicInteger();

		Supplier<GenomeBreeder<PackedCollection>> breeder = () ->
				(GenomeBreeder<PackedCollection>) (g1, g2) ->
						genome(0.0, unique.incrementAndGet(), 0.0);
		Supplier<Supplier<Genome<PackedCollection>>> generator = () ->
				() -> genome(0.0, -unique.incrementAndGet(), 0.0);

		PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore> optimizer =
				new PopulationOptimizer<>(new ScoredPopulation(genomes), ScoredHealth::new,
						ScoredPopulation::new, breeder, generator);
		optimizer.setEliteCount(eliteCount);

		// Initialize the health computation and generator iterate() reads from its fields.
		optimizer.getHealthComputation();
		optimizer.getGenerator();
		return optimizer;
	}

	/**
	 * Returns whether the population holds the given genome object (by identity).
	 *
	 * @param optimizer the optimizer whose population to search
	 * @param genome    the genome object to look for
	 * @return whether the population holds that exact object
	 */
	private static boolean contains(PopulationOptimizer<PackedCollection, PackedCollection, ?, ?> optimizer,
									Genome<PackedCollection> genome) {
		for (Genome<PackedCollection> g : optimizer.getPopulation().getGenomes()) {
			if (g == genome) return true;
		}

		return false;
	}

	/**
	 * With elitism the top K genomes of the previous generation remain in the
	 * population after a breeding iteration, by genome identity, and the best score
	 * is retained.
	 */
	@Test(timeout = 60_000)
	public void topEliteGenomesSurviveBreeding() {
		int popSize = PopulationOptimizer.popSize;
		int maxChildren = PopulationOptimizer.maxChildren;
		boolean breeding = PopulationOptimizer.enableBreeding;

		try {
			PopulationOptimizer.popSize = 4;
			PopulationOptimizer.maxChildren = 4;
			PopulationOptimizer.enableBreeding = true;

			ProjectedGenome best = genome(0.90, 0.1, 0.1);
			ProjectedGenome second = genome(0.80, 0.1, 0.1);
			ProjectedGenome third = genome(0.70, 0.1, 0.1);
			ProjectedGenome fourth = genome(0.60, 0.1, 0.1);

			PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore> optimizer =
					optimizer(2, new ArrayList<>(List.of(best, second, third, fourth)));

			optimizer.iterate();

			Assert.assertTrue("The best genome of the previous generation was discarded",
					contains(optimizer, best));
			Assert.assertTrue("The second-best genome of the previous generation was discarded",
					contains(optimizer, second));
			Assert.assertEquals("The population must keep its target size",
					4, optimizer.getPopulation().size());
			Assert.assertEquals("The best score must be retained after breeding",
					0.90, optimizer.getMaxScore(), 1e-6);
		} finally {
			PopulationOptimizer.popSize = popSize;
			PopulationOptimizer.maxChildren = maxChildren;
			PopulationOptimizer.enableBreeding = breeding;
		}
	}

	/**
	 * When the elite count exceeds the number of genomes available - which is also
	 * the case when fewer than K genomes have a qualifying score, since the
	 * population carries only its scored survivors into the next generation - every
	 * available genome is carried and nothing is invented or duplicated to reach K.
	 */
	@Test(timeout = 60_000)
	public void eliteCountClampedToPopulationSize() {
		int popSize = PopulationOptimizer.popSize;
		int maxChildren = PopulationOptimizer.maxChildren;
		boolean breeding = PopulationOptimizer.enableBreeding;

		try {
			PopulationOptimizer.popSize = 6;
			PopulationOptimizer.maxChildren = 6;
			PopulationOptimizer.enableBreeding = true;

			ProjectedGenome best = genome(0.90, 0.1, 0.1);
			ProjectedGenome second = genome(0.80, 0.1, 0.1);

			PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore> optimizer =
					optimizer(5, new ArrayList<>(List.of(best, second)));

			optimizer.iterate();

			Assert.assertTrue("The only scorers must both be carried over",
					contains(optimizer, best) && contains(optimizer, second));

			long carried = optimizer.getPopulation().getGenomes().stream()
					.filter(g -> g == best || g == second).count();
			Assert.assertEquals("An elite must not be carried more than once", 2, carried);
			Assert.assertEquals("The population is filled to its target size by"
							+ " children and fresh genomes, not by extra elites",
					6, optimizer.getPopulation().size());
		} finally {
			PopulationOptimizer.popSize = popSize;
			PopulationOptimizer.maxChildren = maxChildren;
			PopulationOptimizer.enableBreeding = breeding;
		}
	}

	/**
	 * With breeding disabled, {@link PopulationOptimizer#iterate()} never reaches the
	 * breeding phase where elites are carried, so elitism is inactive: the loaded
	 * population is simply evaluated and re-ordered, with every genome preserved.
	 */
	@Test(timeout = 60_000)
	public void breedingDisabledLeavesElitismInactive() {
		int popSize = PopulationOptimizer.popSize;
		int maxChildren = PopulationOptimizer.maxChildren;
		boolean breeding = PopulationOptimizer.enableBreeding;

		try {
			PopulationOptimizer.popSize = 3;
			PopulationOptimizer.maxChildren = 3;
			PopulationOptimizer.enableBreeding = false;

			ProjectedGenome a = genome(0.30, 0.1, 0.1);
			ProjectedGenome b = genome(0.90, 0.1, 0.1);
			ProjectedGenome c = genome(0.60, 0.1, 0.1);

			PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore> optimizer =
					optimizer(2, new ArrayList<>(List.of(a, b, c)));

			optimizer.iterate();

			Assert.assertEquals("No genome may be added or dropped when breeding is disabled",
					3, optimizer.getPopulation().size());
			Assert.assertTrue("Every loaded genome must survive an iteration with breeding disabled",
					contains(optimizer, a) && contains(optimizer, b) && contains(optimizer, c));
			Assert.assertEquals("The best score must still be reported",
					0.90, optimizer.getMaxScore(), 1e-6);
		} finally {
			PopulationOptimizer.popSize = popSize;
			PopulationOptimizer.maxChildren = maxChildren;
			PopulationOptimizer.enableBreeding = breeding;
		}
	}

	/**
	 * An elite count of zero reproduces the purely generational behavior: the whole
	 * population is replaced, so no genome of the previous generation survives by
	 * identity.
	 */
	@Test(timeout = 60_000)
	public void zeroEliteCountReplacesWholePopulation() {
		int popSize = PopulationOptimizer.popSize;
		int maxChildren = PopulationOptimizer.maxChildren;
		boolean breeding = PopulationOptimizer.enableBreeding;

		try {
			PopulationOptimizer.popSize = 4;
			PopulationOptimizer.maxChildren = 4;
			PopulationOptimizer.enableBreeding = true;

			ProjectedGenome best = genome(0.90, 0.1, 0.1);
			ProjectedGenome second = genome(0.80, 0.1, 0.1);
			ProjectedGenome third = genome(0.70, 0.1, 0.1);
			ProjectedGenome fourth = genome(0.60, 0.1, 0.1);

			PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore> optimizer =
					optimizer(0, new ArrayList<>(List.of(best, second, third, fourth)));

			optimizer.iterate();

			Assert.assertFalse("No previous genome may survive without elitism",
					contains(optimizer, best) || contains(optimizer, second)
							|| contains(optimizer, third) || contains(optimizer, fourth));
		} finally {
			PopulationOptimizer.popSize = popSize;
			PopulationOptimizer.maxChildren = maxChildren;
			PopulationOptimizer.enableBreeding = breeding;
		}
	}

	/** A negative elite count is rejected. */
	@Test(timeout = 60_000)
	public void negativeEliteCountRejected() {
		PopulationOptimizer<PackedCollection, PackedCollection, ScoredOrganism, HealthScore> optimizer =
				optimizer(0, new ArrayList<>(List.of(genome(0.5, 0.1, 0.1))));

		try {
			optimizer.setEliteCount(-1);
			Assert.fail("A negative elite count must throw IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
