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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Genome;
import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pins who owns the population records, and which record is being rendered.
 *
 * <p>The records outlive the list that holds them: the process rebuilds that
 * list whenever breeding moves the population past the genomes the records
 * describe. A caller that took hold of the list itself went on reading a set
 * of records the process had stopped writing to — every arrangement reading as
 * unscored while the run reported real progress, because the scores were
 * landing on records the caller could not see.</p>
 *
 * <p>So the list is not handed out. These pin that it is not, and that the
 * process can say which record it is rendering, which is the same question
 * asked of a moment rather than of a run.</p>
 */
public class ArrangementGenerationRecordsTest extends TestSuiteBase {

	/** A genome that answers to a signature and nothing else. */
	private static class SignedGenome implements Genome<PackedCollection> {
		/** The signature this genome reports. */
		private final String signature;

		/**
		 * Creates a genome reporting the given signature.
		 *
		 * @param signature the signature to report
		 */
		SignedGenome(String signature) {
			this.signature = signature;
		}

		@Override
		public String signature() { return signature; }

		@Override
		public int count() { return 0; }

		@Override
		public Chromosome<PackedCollection> valueAt(int pos) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Gives the test the entry points a running optimizer would otherwise be
	 * the only caller of.
	 */
	private static class DrivableProcess extends ArrangementGenerationProcess {
		/** Creates a process whose record file does not exist. */
		DrivableProcess() {
			super("results/no-such-population-file.xml");
		}

		/**
		 * Reports that the genome carrying the given signature is now being
		 * rendered.
		 *
		 * @param signature the signature of the genome being rendered
		 */
		void render(String signature) { beginRender(signature); }

		/** Reports that nothing is being rendered. */
		void finishRendering() { endRender(); }
	}

	/**
	 * Records carrying the given signatures.
	 *
	 * @param signatures one signature per record
	 * @return the records
	 */
	private List<GenomicNetwork> records(String... signatures) {
		List<GenomicNetwork> records = new ArrayList<>();

		for (String signature : signatures) {
			records.add(new GenomicNetwork(records.size(),
					new SignedGenome(signature)));
		}

		return records;
	}

	/**
	 * The caller's list is copied in, not adopted.
	 *
	 * <p>Otherwise the process and the caller share one list, and either can
	 * empty or reorder it under the other.</p>
	 */
	@Test(timeout = 30000)
	public void theRecordsGivenToTheProcessAreNotTheCallersList() {
		DrivableProcess process = new DrivableProcess();
		List<GenomicNetwork> mine = records("a", "b");

		process.setNetworks(mine);
		mine.clear();

		Assert.assertEquals(2, process.getNetworks().size());
	}

	/** What the process returns is a snapshot, so nothing can write to it. */
	@Test(timeout = 30000)
	public void theRecordsTheProcessReturnsCannotBeWrittenTo() {
		DrivableProcess process = new DrivableProcess();
		process.setNetworks(records("a"));

		try {
			process.getNetworks().clear();
			Assert.fail("Expected the returned records to be immutable");
		} catch (UnsupportedOperationException expected) {
			Assert.assertEquals(1, process.getNetworks().size());
		}
	}

	/**
	 * A caller that asks again sees the score; one that kept the earlier
	 * answer does not.
	 *
	 * <p>This is the failure as it was seen: the run reported progress while
	 * every arrangement listed read as having no audio yet. Both statements
	 * were true of the list each was looking at.</p>
	 */
	@Test(timeout = 30000)
	public void aDeliveredScoreIsVisibleToWhoeverAsksAgain() {
		DrivableProcess process = new DrivableProcess();
		process.setNetworks(records("a"));

		List<GenomicNetwork> before = process.getNetworks();
		process.setNetworks(records("a", "b"));

		Assert.assertEquals(1, before.size());
		Assert.assertEquals(2, process.getNetworks().size());
	}

	/** The record being rendered is the one carrying the rendered signature. */
	@Test(timeout = 30000)
	public void theRenderingRecordIsTheOneBeingRendered() {
		DrivableProcess process = new DrivableProcess();
		process.setNetworks(records("a", "b"));

		process.render("b");

		Assert.assertNotNull(process.getRendering());
		Assert.assertEquals("b",
				process.getRendering().getGenome().signature());
	}

	/** Nothing is reported as rendering before a render has begun. */
	@Test(timeout = 30000)
	public void nothingIsRenderingBeforeARenderBegins() {
		DrivableProcess process = new DrivableProcess();
		process.setNetworks(records("a"));

		Assert.assertNull(process.getRendering());
	}

	/**
	 * Rendering stops being reported once the cycle is done, so a finished run
	 * does not leave a row lit for a render that is not happening.
	 */
	@Test(timeout = 30000)
	public void nothingIsRenderingOnceTheCycleFinishes() {
		DrivableProcess process = new DrivableProcess();
		process.setNetworks(records("a"));

		process.render("a");
		process.finishRendering();

		Assert.assertNull(process.getRendering());
	}

	/** A signature no record carries leaves nothing reported as rendering. */
	@Test(timeout = 30000)
	public void aRenderMatchingNoRecordReportsNothing() {
		DrivableProcess process = new DrivableProcess();
		process.setNetworks(records("a"));

		process.render("matches-nothing");

		Assert.assertNull(process.getRendering());
	}

	/** The listener is told which record rendering moved to. */
	@Test(timeout = 30000)
	public void theListenerIsToldWhatIsBeingRendered() {
		DrivableProcess process = new DrivableProcess();
		AtomicReference<GenomicNetwork> told = new AtomicReference<>();

		process.setNetworks(records("a", "b"));
		process.setRenderListener(told::set);
		process.render("b");

		Assert.assertNotNull(told.get());
		Assert.assertEquals("b", told.get().getGenome().signature());
	}

	/** The listener is told when rendering stops, so a display can clear. */
	@Test(timeout = 30000)
	public void theListenerIsToldWhenRenderingStops() {
		DrivableProcess process = new DrivableProcess();
		AtomicReference<GenomicNetwork> told = new AtomicReference<>();

		process.setNetworks(records("a"));
		process.setRenderListener(told::set);
		process.render("a");
		process.finishRendering();

		Assert.assertNull(told.get());
	}

	/**
	 * Reading the records while they are being rebuilt yields one state or the
	 * other, never a list caught midway through the rebuild.
	 *
	 * <p>The rebuild runs on the thread performing the evaluations while the
	 * display reads from its own, so this is the overlap that sharing the list
	 * outright would have exposed.</p>
	 */
	@Test(timeout = 30000)
	public void readingDuringARebuildNeverSeesAPartialList() throws Exception {
		DrivableProcess process = new DrivableProcess();
		List<GenomicNetwork> four = records("a", "b", "c", "d");
		process.setNetworks(four);

		AtomicReference<Integer> odd = new AtomicReference<>();

		Thread writer = new Thread(() -> {
			for (int i = 0; i < 2000; i++) {
				process.setNetworks(four);
			}
		});

		Thread reader = new Thread(() -> {
			for (int i = 0; i < 2000; i++) {
				int size = process.getNetworks().size();
				if (size != 4) odd.compareAndSet(null, size);
			}
		});

		writer.start();
		reader.start();
		writer.join();
		reader.join();

		Assert.assertNull("Records were read midway through a rebuild: " +
				odd.get(), odd.get());
	}

	/**
	 * A score delivered for a record attaches to the record the process holds,
	 * so asking the process again shows it.
	 */
	@Test(timeout = 30000)
	public void aScoredRecordReportsItsScore() {
		DrivableProcess process = new DrivableProcess();
		List<GenomicNetwork> one = records("a");
		one.get(0).setHealthScore(new AudioHealthScore(
				0, 0.5, "results/no-such-render.wav", null, null));

		process.setNetworks(one);

		Assert.assertNotNull(process.getNetworks().get(0).getHealthScore());
	}
}
