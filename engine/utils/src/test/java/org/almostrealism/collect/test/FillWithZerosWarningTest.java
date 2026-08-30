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
import org.almostrealism.io.Console;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pins what the report of a fill with zeros costs when there is nothing to
 * report.
 *
 * <p>A fill with zeros is reported above two values, so that a
 * {@link org.almostrealism.algebra.Pair} — two values, zeroed this way in the
 * ordinary course of things — does not report.</p>
 *
 * <p>What this deliberately does not cover is a fill with zeros itself. Doing
 * so means calling {@code fill(0.0)} from checked-in source, and the code
 * policy forbids that everywhere — "zeroing is wrong everywhere, so this rule
 * holds for tests as well" — with no exemption. Writing the call in a way the
 * detector does not recognise would be evading a rule rather than honouring
 * it, so the reporting behaviour is left uncovered and said so here rather
 * than covered dishonestly.</p>
 *
 * <p>Covering it would mean narrowing that rule for this one file, which is a
 * change to an enforcement mechanism and belongs to whoever owns the policy,
 * not to a test that finds the rule inconvenient.</p>
 */
public class FillWithZerosWarningTest extends TestSuiteBase {
	/** What the fill report says, in part. */
	private static final String REPORT = "clear() does this with a kernel";

	/**
	 * Returns everything the console was told while the given work ran.
	 *
	 * @param work the work to run
	 * @return the lines printed
	 */
	private List<String> printedDuring(Runnable work) {
		List<String> printed = new ArrayList<>();
		Consumer<String> listener = printed::add;

		Console.root().addListener(listener);

		try {
			work.run();
		} finally {
			Console.root().removeListener(listener);
		}

		return printed;
	}

	/**
	 * Returns whether the fill report appears among the given lines.
	 *
	 * @param printed the lines printed
	 * @return whether anything reported a fill with zeros
	 */
	private boolean reported(List<String> printed) {
		return printed.stream().anyMatch(line -> line.contains(REPORT));
	}

	/** Filling with anything other than zeros says nothing, at any size. */
	@Test(timeout = 60000)
	public void fillingWithSomethingElseIsNotReported() {
		PackedCollection many = new PackedCollection(new TraversalPolicy(128));

		Assert.assertFalse(reported(printedDuring(() -> many.fill(1.0))));
	}

	/** Filling one element with a value says nothing either. */
	@Test(timeout = 60000)
	public void aSingleValueIsNotReported() {
		PackedCollection one = new PackedCollection(new TraversalPolicy(1));

		Assert.assertFalse(reported(printedDuring(() -> one.fill(1.0))));
	}

	/**
	 * Asking a report where it came from is off unless something turns it on.
	 *
	 * <p>A stack per report would drown a log being read for anything else, so
	 * this is for a build that is hunting these and not for every build.</p>
	 */
	@Test(timeout = 60000)
	public void theOriginIsNotAskedForByDefault() {
		Assert.assertFalse("A trace per report would drown the log it is in",
				PackedCollection.enableFillOrigin);
	}
}
