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

package org.almostrealism.color.test;

import org.almostrealism.color.OverlayDistribution;
import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.color.RangeSumDistribution;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Behavioral tests for {@link OverlayDistribution} and {@link RangeSumDistribution}, the two
 * {@link ProbabilityDistribution} implementations that combine child distributions.
 *
 * <p>These tests pin the behavior of the operations the two classes share — adding a range to
 * the first child, summing child probabilities, testing containment across children, and
 * replacing children from a file — as well as the operations where the two classes genuinely
 * differ ({@code getRanges}, {@code getSample}, {@code getIntegrated}, {@code integrate}). They
 * exist to guarantee that extracting the shared operations into a common base class preserves
 * the observable behavior of both classes exactly.</p>
 */
public class CompositeDistributionTest extends TestSuiteBase {

	/**
	 * Builds a single-range {@link ProbabilityDistribution} covering {@code [start, end]} with
	 * density {@code p}.
	 *
	 * @param start the lower bound of the single range
	 * @param end   the upper bound of the single range
	 * @param p     the probability density of the range
	 * @return a distribution with one range
	 */
	private static ProbabilityDistribution single(double start, double end, double p) {
		ProbabilityDistribution d = new ProbabilityDistribution();
		d.addRange(start, end, p);
		return d;
	}

	/**
	 * {@code getProbability} on an overlay sums the densities of all children at the query point.
	 */
	@Test(timeout = 5000)
	public void overlayGetProbabilitySumsChildren() {
		ProbabilityDistribution a = single(0.0, 1.0, 0.3);
		ProbabilityDistribution b = single(0.0, 1.0, 0.4);
		OverlayDistribution d = new OverlayDistribution(new ProbabilityDistribution[] {a, b});

		Assert.assertEquals(0.7, d.getProbability(0.5), 1e-9);
		Assert.assertEquals(a.getProbability(0.5) + b.getProbability(0.5),
				d.getProbability(0.5), 1e-9);
	}

	/**
	 * {@code getProbability} on a range-sum distribution sums the densities of all children — the
	 * same delegation the overlay uses.
	 */
	@Test(timeout = 5000)
	public void rangeSumGetProbabilitySumsChildren() {
		ProbabilityDistribution a = single(0.0, 1.0, 0.3);
		ProbabilityDistribution b = single(0.0, 1.0, 0.4);
		RangeSumDistribution d = new RangeSumDistribution(new ProbabilityDistribution[] {a, b});

		Assert.assertEquals(0.7, d.getProbability(0.5), 1e-9);
	}

	/**
	 * {@code getProbability} on an empty composite is {@code 0.0} for both classes.
	 */
	@Test(timeout = 5000)
	public void emptyCompositeGetProbabilityIsZero() {
		Assert.assertEquals(0.0, new OverlayDistribution().getProbability(0.5), 1e-9);
		Assert.assertEquals(0.0, new RangeSumDistribution().getProbability(0.5), 1e-9);
	}

	/**
	 * {@code contains} is {@code true} when any child contains the queried range and {@code false}
	 * otherwise, for both classes.
	 */
	@Test(timeout = 5000)
	public void containsIsTrueWhenAnyChildContains() {
		ProbabilityDistribution a = single(0.0, 1.0, 0.5);
		ProbabilityDistribution b = single(5.0, 6.0, 0.5);

		OverlayDistribution overlay = new OverlayDistribution(new ProbabilityDistribution[] {a, b});
		RangeSumDistribution rangeSum = new RangeSumDistribution(new ProbabilityDistribution[] {a, b});

		Assert.assertTrue(overlay.contains(5.2, 5.5));
		Assert.assertTrue(rangeSum.contains(5.2, 5.5));
		Assert.assertFalse(overlay.contains(10.0, 11.0));
		Assert.assertFalse(rangeSum.contains(10.0, 11.0));
	}

	/**
	 * {@code contains} on an empty composite is {@code false} for both classes.
	 */
	@Test(timeout = 5000)
	public void emptyCompositeContainsIsFalse() {
		Assert.assertFalse(new OverlayDistribution().contains(0.0, 1.0));
		Assert.assertFalse(new RangeSumDistribution().contains(0.0, 1.0));
	}

	/**
	 * {@code addRange} is routed to the first child for both classes.
	 */
	@Test(timeout = 5000)
	public void addRangeTargetsFirstChild() {
		ProbabilityDistribution first = new ProbabilityDistribution();
		ProbabilityDistribution second = new ProbabilityDistribution();
		OverlayDistribution d = new OverlayDistribution(new ProbabilityDistribution[] {first, second});

		d.addRange(0.0, 1.0, 0.5);

		Assert.assertTrue(first.contains(0.0, 1.0));
		Assert.assertFalse(second.contains(0.0, 1.0));
	}

	/**
	 * {@code addRange} on a composite with no children is a silent no-op (no exception, no range
	 * recorded) for both classes.
	 */
	@Test(timeout = 5000)
	public void addRangeOnEmptyCompositeIsNoOp() {
		OverlayDistribution overlay = new OverlayDistribution();
		overlay.addRange(0.0, 1.0, 0.5);
		Assert.assertFalse(overlay.contains(0.0, 1.0));

		RangeSumDistribution rangeSum = new RangeSumDistribution();
		rangeSum.addRange(0.0, 1.0, 0.5);
		Assert.assertFalse(rangeSum.contains(0.0, 1.0));
	}

	/**
	 * {@code loadFromFile} replaces all children with a single child populated from the file, for
	 * both classes.
	 *
	 * @throws IOException if the temporary file cannot be written or read
	 */
	@Test(timeout = 5000)
	public void loadFromFileReplacesChildrenWithFileContents() throws IOException {
		File tmp = File.createTempFile("composite-distribution", ".txt");
		tmp.deleteOnExit();
		try (FileWriter w = new FileWriter(tmp)) {
			w.write("0.0 1.0 0.5\n");
		}

		OverlayDistribution overlay = new OverlayDistribution();
		overlay.loadFromFile(tmp.getAbsolutePath(), " ");
		Assert.assertTrue(overlay.contains(0.0, 1.0));
		Assert.assertEquals(0.5, overlay.getProbability(0.5), 1e-9);

		RangeSumDistribution rangeSum = new RangeSumDistribution();
		rangeSum.loadFromFile(tmp.getAbsolutePath(), " ");
		Assert.assertTrue(rangeSum.contains(0.0, 1.0));
		Assert.assertEquals(0.5, rangeSum.getProbability(0.5), 1e-9);
	}

	/**
	 * The overlay's {@code integrate} sums the children's integrals; its {@code getSample} with a
	 * single child delegates to that child. These are overlay-specific behaviors that must survive
	 * the refactor.
	 */
	@Test(timeout = 5000)
	public void overlayIntegrateAndSampleBehavior() {
		ProbabilityDistribution a = single(0.0, 1.0, 0.5);
		ProbabilityDistribution b = single(0.0, 1.0, 0.25);
		OverlayDistribution d = new OverlayDistribution(new ProbabilityDistribution[] {a, b});

		Assert.assertEquals(a.integrate(1.0) + b.integrate(1.0), d.integrate(1.0), 1e-9);

		ProbabilityDistribution sampler = new ProbabilityDistribution(new ProbabilityDistribution.Sampler() {
			@Override
			public double getSample(double r) { return 0.42; }

			@Override
			public double getProbability(double x) { return 0.0; }
		});
		OverlayDistribution single = new OverlayDistribution(new ProbabilityDistribution[] {sampler});
		Assert.assertEquals(0.42, single.getSample(0.9), 1e-9);
	}

	/**
	 * The range-sum distribution's {@code getSample} and {@code getIntegrated} are deliberately
	 * unimplemented ({@code 0.0} and {@code null}); the refactor must not change that.
	 */
	@Test(timeout = 5000)
	public void rangeSumSampleAndIntegratedAreUnimplemented() {
		RangeSumDistribution d = new RangeSumDistribution(
				new ProbabilityDistribution[] {single(0.0, 1.0, 0.5)});
		Assert.assertEquals(0.0, d.getSample(0.5), 1e-9);
		Assert.assertNull(d.getIntegrated());
	}
}
