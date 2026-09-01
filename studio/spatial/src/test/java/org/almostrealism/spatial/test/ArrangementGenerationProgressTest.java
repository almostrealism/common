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

package org.almostrealism.spatial.test;

import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins how far through a cycle the generation process reports itself to be.
 *
 * <p>The count of finished work is what a progress bar divides, so it has to
 * mean one thing only: an evaluation the cycle has actually delivered. Counting
 * scored records instead is what let the bar fill early — a record is not a
 * unit of work, and the record list a caller holds may be one the cycle
 * stopped writing to.</p>
 *
 * <p>These run with no optimizer and no scene, so the accounting is exercised
 * on its own rather than from behind a full arrangement render.</p>
 */
public class ArrangementGenerationProgressTest extends TestSuiteBase {

	/** The signature of the one evaluation these tests deliver. */
	private static final String DELIVERED = "delivered-signature";

	/**
	 * Gives the test the score-delivery entry point, which is otherwise reached
	 * only from a running optimizer.
	 */
	private static class DeliverableProcess extends ArrangementGenerationProcess {
		/** Creates a process whose record file does not exist. */
		DeliverableProcess() {
			super("results/no-such-population-file.xml");
		}

		/**
		 * Delivers one health evaluation, as a running optimizer would.
		 *
		 * @param signature the genome signature the evaluation was for
		 */
		void deliver(String signature) {
			attachScore(signature, new AudioHealthScore(0, 0.0));
		}
	}

	/** Records standing in for a population of the given size. */
	private List<GenomicNetwork> records(int count) {
		List<GenomicNetwork> records = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			records.add(new GenomicNetwork(i, null));
		}

		return records;
	}

	/** A cycle that has delivered nothing has done none of its work. */
	@Test(timeout = 30000)
	public void nothingDeliveredIsNoProgress() {
		DeliverableProcess process = new DeliverableProcess();
		process.setNetworks(records(4));

		ArrangementGenerationProcess.Progress cycle = process.getProgress();

		Assert.assertEquals(0, cycle.completed());
		Assert.assertEquals(4, cycle.total());
		Assert.assertEquals(4, cycle.remaining());
		Assert.assertEquals(0.0, cycle.fraction(), 0.0);
	}

	/** Each delivered evaluation moves the cycle on by exactly one. */
	@Test(timeout = 30000)
	public void eachDeliveredEvaluationAdvancesTheCycleOnce() {
		DeliverableProcess process = new DeliverableProcess();
		process.setNetworks(records(4));

		process.deliver("a");
		process.deliver("b");

		ArrangementGenerationProcess.Progress cycle = process.getProgress();

		Assert.assertEquals(2, cycle.completed());
		Assert.assertEquals(2, cycle.remaining());
		Assert.assertEquals(0.5, cycle.fraction(), 0.0);
	}

	/**
	 * How many records hold a score is not how much of the cycle is done.
	 *
	 * <p>Records carry the scores of the cycle before until the list is rebuilt
	 * against the new population — so counting records counts a set that is
	 * fully scored before the cycle describing it has begun. The record count
	 * runs ahead of the work, which is what filled the bar early.</p>
	 *
	 * <p>Here three of four records already hold a score and one evaluation has
	 * been delivered. Counting records would call the cycle three quarters
	 * done; one evaluation of four is what has actually happened.</p>
	 */
	@Test(timeout = 30000)
	public void completedWorkIsNotTheNumberOfScoredRecords() {
		DeliverableProcess process = new DeliverableProcess();
		List<GenomicNetwork> records = records(4);

		for (int i = 0; i < 3; i++) {
			records.get(i).setHealthScore(new AudioHealthScore(
					0, 0.0, "results/no-such-render.wav", null, null));
		}

		process.setNetworks(records);
		process.deliver(DELIVERED);

		ArrangementGenerationProcess.Progress cycle = process.getProgress();

		Assert.assertEquals("Scored records are not delivered evaluations",
				1, cycle.completed());
		Assert.assertEquals(3, cycle.remaining());
		Assert.assertEquals(0.25, cycle.fraction(), 0.0);
	}

	/**
	 * A score matching no record is still an evaluation the cycle performed.
	 * Records fall out of step with the population routinely — every cycle
	 * breeds new genomes — so progress that counted only matches would stall
	 * exactly when the population had moved on.
	 */
	@Test(timeout = 30000)
	public void anEvaluationMatchingNoRecordStillCounts() {
		DeliverableProcess process = new DeliverableProcess();
		process.setNetworks(records(4));

		process.deliver("matches-nothing");

		Assert.assertEquals(1, process.getProgress().completed());
	}

	/** A cycle never reports more work outstanding than it has left. */
	@Test(timeout = 30000)
	public void remainingWorkIsNeverNegative() {
		DeliverableProcess process = new DeliverableProcess();
		process.setNetworks(records(2));

		process.deliver("a");
		process.deliver("b");
		process.deliver("c");

		Assert.assertEquals(0, process.getProgress().remaining());
	}

	/**
	 * Before a run is prepared there is no population to size the cycle by, and
	 * no records either. Saying so plainly is what keeps the bar indeterminate
	 * rather than showing a fraction of a total nobody knows.
	 */
	@Test(timeout = 30000)
	public void anUnknownCycleSizeIsReportedAsUnknown() {
		DeliverableProcess process = new DeliverableProcess();

		ArrangementGenerationProcess.Progress cycle = process.getProgress();

		Assert.assertFalse(cycle.isKnown());
		Assert.assertEquals(0, cycle.total());
		Assert.assertEquals(0.0, cycle.fraction(), 0.0);
	}
}
