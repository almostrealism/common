/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins what a reservation holds before anything has been written to it.
 *
 * <p>Almost nothing in this project zeroes a collection it has just reserved,
 * and almost everything expects to read zero from one. That expectation is
 * either a property of the memory providers or a bug that has been getting
 * away with it, and which one it is cannot be settled by reading the Java
 * side: the reservation is made by native code, and the absence of a memset
 * in the wrapper says nothing about what the wrapper calls.</p>
 *
 * <p>So it is measured instead. Whether the reservation is zeroed by the
 * device, the driver, or the operating system handing over a fresh page is
 * not distinguished here and does not need to be — what the rest of the
 * project relies on is only that it reads as zero.</p>
 *
 * <p>Run this against whichever backend is in question. A provider that fails
 * it is not a provider this project's code can be run on unchanged, which
 * makes this the place that would say so.</p>
 */
public class FreshReservationTest extends TestSuiteBase {
	/** How many reservations to make. */
	private static final int RESERVATIONS = 64;

	/** How many values each one holds. */
	private static final int LENGTH = 1024;

	/**
	 * Every value of a reservation reads as zero before anything writes to it.
	 *
	 * <p>Held all at once rather than one at a time, so that each reservation
	 * is a distinct region rather than possibly the same region handed back
	 * repeatedly — which would say nothing about the second one.</p>
	 */
	@Test(timeout = 120000)
	public void aReservationReadsAsZero() {
		List<PackedCollection> held = new ArrayList<>();

		try {
			for (int i = 0; i < RESERVATIONS; i++) {
				held.add(new PackedCollection(new TraversalPolicy(LENGTH)));
			}

			for (int i = 0; i < held.size(); i++) {
				double[] values = held.get(i).toArray();

				for (int j = 0; j < values.length; j++) {
					Assert.assertEquals("Reservation " + i + " value " + j
							+ " was not zero", 0.0, values[j], 0.0);
				}
			}
		} finally {
			held.forEach(PackedCollection::destroy);
		}
	}

	/**
	 * A reservation made after others were released still reads as zero.
	 *
	 * <p>This is the case where a region could be handed out a second time,
	 * carrying whatever the previous holder left in it. Written to before
	 * being released, so that anything reused would be visibly not zero
	 * rather than accidentally still zero.</p>
	 */
	@Test(timeout = 120000)
	public void aReservationAfterAReleaseReadsAsZero() {
		List<PackedCollection> first = new ArrayList<>();

		try {
			for (int i = 0; i < RESERVATIONS; i++) {
				PackedCollection c = new PackedCollection(new TraversalPolicy(LENGTH));
				c.fill(1.0);
				first.add(c);
			}
		} finally {
			first.forEach(PackedCollection::destroy);
		}

		List<PackedCollection> second = new ArrayList<>();

		try {
			for (int i = 0; i < RESERVATIONS; i++) {
				second.add(new PackedCollection(new TraversalPolicy(LENGTH)));
			}

			for (int i = 0; i < second.size(); i++) {
				double[] values = second.get(i).toArray();

				for (int j = 0; j < values.length; j++) {
					Assert.assertEquals("Reused reservation " + i + " value " + j
							+ " carried " + values[j], 0.0, values[j], 0.0);
				}
			}
		} finally {
			second.forEach(PackedCollection::destroy);
		}
	}
}
