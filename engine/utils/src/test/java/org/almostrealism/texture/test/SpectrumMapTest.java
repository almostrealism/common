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

package org.almostrealism.texture.test;

import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.texture.IntensityMap;
import org.almostrealism.texture.SpectrumMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Tests for {@link SpectrumMap}, which couples an {@link IntensityMap} to a
 * {@link ProbabilityDistribution}. When no separate distribution is supplied the
 * map acts as its own distribution (via the superclass); when one is supplied all
 * distribution operations delegate to it.
 */
public class SpectrumMapTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-9;

	/**
	 * A {@link SpectrumMap} subclass that exposes the protected {@code getRanges}
	 * hook so the "separate distribution" guard can be exercised.
	 */
	private static class ProbeSpectrumMap extends SpectrumMap {
		/**
		 * Constructs a probe map.
		 *
		 * @param m the base intensity map
		 * @param spec the separate probability distribution, or {@code null}
		 */
		ProbeSpectrumMap(IntensityMap m, ProbabilityDistribution spec) {
			super(m, spec);
		}

		/**
		 * Exposes the protected ranges accessor.
		 *
		 * @return the range triples
		 */
		double[][] ranges() {
			return getRanges();
		}
	}

	/**
	 * Verifies {@link SpectrumMap#getIntensity(double, double, double)} forwards to
	 * the configured base intensity map.
	 */
	@Test(timeout = 30000)
	public void intensityDelegatesToMap() {
		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5 * u);
		Assert.assertEquals(0.3, map.getIntensity(0.6, 9.0, 9.0), TOL);
	}

	/**
	 * Verifies the intensity-map accessor round-trips.
	 */
	@Test(timeout = 30000)
	public void intensityMapAccessorRoundTrips() {
		SpectrumMap map = new SpectrumMap();
		Assert.assertNull(map.getIntensityMap());

		IntensityMap base = (u, v, w) -> 1.0;
		map.setIntensityMap(base);
		Assert.assertSame(base, map.getIntensityMap());
	}

	/**
	 * Verifies that with no separate distribution, the map returns itself from
	 * {@link SpectrumMap#getProbabilityDistribution()} and populates its own ranges.
	 */
	@Test(timeout = 30000)
	public void actsAsOwnDistributionWhenUnset() {
		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5);
		Assert.assertSame(map, map.getProbabilityDistribution());

		map.addRange(400.0, 500.0, 1.0);
		Assert.assertEquals(1, map.getNodeCount());
		Assert.assertTrue(map.contains(410.0, 420.0));
		Assert.assertFalse(map.contains(600.0, 700.0));
	}

	/**
	 * Verifies {@link SpectrumMap#getSample(double, double, double)} maps the
	 * intensity through the single range: intensity 0.5 over range [400, 500]
	 * samples the midpoint 450.
	 */
	@Test(timeout = 30000)
	public void sampleFromIntensityHitsExpectedWavelength() {
		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5);
		map.addRange(400.0, 500.0, 1.0);

		Assert.assertEquals(450.0, map.getSample(1.0, 2.0, 3.0), 1e-6);
	}

	/**
	 * Verifies that when a separate distribution is supplied every distribution
	 * operation delegates to it rather than to the superclass state.
	 */
	@Test(timeout = 30000)
	public void operationsDelegateToSeparateDistribution() {
		ProbabilityDistribution spec = new ProbabilityDistribution();
		spec.addRange(0.0, 1.0, 1.0);

		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5, spec);

		Assert.assertSame(spec, map.getProbabilityDistribution());
		Assert.assertEquals(spec.getNodeCount(), map.getNodeCount());
		Assert.assertEquals(spec.contains(0.2, 0.3), map.contains(0.2, 0.3));
		Assert.assertEquals(spec.getSample(0.5), map.getSample(0.5), TOL);
		Assert.assertEquals(spec.integrate(0.5), map.integrate(0.5), TOL);
		Assert.assertEquals(spec.getProbability(0.5), map.getProbability(0.5), TOL);
	}

	/**
	 * Verifies {@link SpectrumMap#addRange(double, double, double)} and
	 * {@link SpectrumMap#setRangeProbability(int, double)} mutate the delegate
	 * distribution.
	 */
	@Test(timeout = 30000)
	public void mutationsDelegateToSeparateDistribution() {
		ProbabilityDistribution spec = new ProbabilityDistribution();
		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5, spec);

		map.addRange(0.0, 1.0, 0.25);
		Assert.assertEquals(1, spec.getNodeCount());
		Assert.assertEquals(0.25, spec.getRangeProbability(0), TOL);

		map.setRangeProbability(0, 0.5);
		Assert.assertEquals(0.5, spec.getRangeProbability(0), TOL);
	}

	/**
	 * Writes a whitespace-delimited distribution file with the given range lines.
	 *
	 * @param lines the {@code start end probability} rows
	 * @return the temporary file
	 * @throws IOException if the file cannot be written
	 */
	private File writeRangeFile(String... lines) throws IOException {
		File file = File.createTempFile("spectrum", ".txt");
		file.deleteOnExit();
		Files.write(file.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/**
	 * Verifies {@link SpectrumMap#loadFromFile(String, String)} populates the map's
	 * own distribution with one range per line when no delegate is set.
	 */
	@Test(timeout = 30000)
	public void loadFromFilePopulatesOwnDistribution() throws IOException {
		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5);
		map.loadFromFile(writeRangeFile("0.0 0.1 1.0", "0.2 0.3 0.5").getPath(), " ");

		Assert.assertEquals(2, map.getNodeCount());
		Assert.assertTrue(map.contains(0.05, 0.08));
	}

	/**
	 * Verifies {@link SpectrumMap#loadFromFile(String, String)} delegates to a
	 * separate distribution when one is set.
	 */
	@Test(timeout = 30000)
	public void loadFromFileDelegatesToSeparateDistribution() throws IOException {
		ProbabilityDistribution spec = new ProbabilityDistribution();
		SpectrumMap map = new SpectrumMap((u, v, w) -> 0.5, spec);
		map.loadFromFile(writeRangeFile("0.0 0.1 1.0").getPath(), " ");

		Assert.assertEquals(1, spec.getNodeCount());
	}

	/**
	 * Verifies the protected {@code getRanges} refuses access when a separate
	 * distribution is in use, since those ranges are private to the delegate.
	 */
	@Test(timeout = 30000)
	public void getRangesIsRefusedWithSeparateDistribution() {
		ProbeSpectrumMap map = new ProbeSpectrumMap((u, v, w) -> 0.5, new ProbabilityDistribution());

		boolean threw = false;
		try {
			map.ranges();
		} catch (RuntimeException e) {
			threw = true;
		}
		Assert.assertTrue("expected getRanges to be refused", threw);
	}
}
