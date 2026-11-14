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

package org.almostrealism.render.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.SurfaceLight;
import org.almostrealism.primitives.PointLightGrid;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests {@link PointLightGrid} as a {@link SurfaceLight}, and the default
 * {@link SurfaceLight#lightingCalculation(ShadableSurface, ShaderContext)} that sums the
 * contributions of its samples.
 *
 * <p>The grid tests use rectangular grids in both orientations: the lights of a grid are its
 * samples, so every cell must hold exactly one light at its own position, with no empty
 * entries for the lighting sum to reach.</p>
 */
public class PointLightGridTest extends TestSuiteBase implements RGBFeatures {

	/**
	 * A {@link Light} whose contribution is a constant colour, recording each context it is
	 * asked to light so that the per-sample context handling can be checked.
	 */
	private static final class ConstantLight implements Light {
		/** The colour this light contributes. */
		private final RGB color;
		/** The contexts this light was asked to light, in order. */
		private final List<ShaderContext> contexts = new ArrayList<>();

		private ConstantLight(double r, double g, double b) { this.color = new RGB(r, g, b); }

		@Override
		public void setIntensity(double intensity) { }

		@Override
		public void setColor(RGB color) { }

		@Override
		public double getIntensity() { return 1.0; }

		@Override
		public RGB getColor() { return color; }

		@Override
		public Producer<PackedCollection> getColorAt(Producer<PackedCollection> point) {
			return RGBFeatures.getInstance().value(color);
		}

		@Override
		public Producer<PackedCollection> lightingCalculation(ShadableSurface surface, ShaderContext context) {
			contexts.add(context);
			return RGBFeatures.getInstance().value(color);
		}
	}

	/** A {@link SurfaceLight} whose samples are a fixed array of lights. */
	private static final class FixedSurfaceLight implements SurfaceLight {
		/** The samples of this light. */
		private final Light[] samples;

		private FixedSurfaceLight(Light... samples) { this.samples = samples; }

		@Override
		public Light[] getSamples(int total) { return samples; }

		@Override
		public Light[] getSamples() { return samples; }

		@Override
		public void setIntensity(double intensity) { }

		@Override
		public void setColor(RGB color) { }

		@Override
		public double getIntensity() { return 1.0; }

		@Override
		public RGB getColor() { return new RGB(1.0, 1.0, 1.0); }

		@Override
		public Producer<PackedCollection> getColorAt(Producer<PackedCollection> point) { return null; }
	}

	/**
	 * Asserts that a grid of the given dimensions holds one light per cell, each at the
	 * position of its own cell, and that the total intensity is shared evenly.
	 *
	 * @param width   number of lights along the X axis
	 * @param height  number of lights along the Y axis
	 */
	private void assertGrid(int width, int height) {
		double xSpace = 2.0;
		double ySpace = 3.0;
		PointLightGrid grid = new PointLightGrid(width, height, xSpace, ySpace);
		PointLight[] samples = grid.getSamples();

		Assert.assertEquals(width * height, samples.length);

		Set<String> positions = new HashSet<>();
		for (int i = 0; i < samples.length; i++) {
			Assert.assertNotNull("Sample " + i + " of a " + width + "x" + height + " grid", samples[i]);
			positions.add(samples[i].getLocation().getX() + "," + samples[i].getLocation().getY());
			Assert.assertEquals(1.0 / samples.length, samples[i].getIntensity(), 1e-12);
		}

		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				Assert.assertTrue("Missing light at cell (" + x + ", " + y + ")",
						positions.contains((x * xSpace) + "," + (y * ySpace)));
				PointLight light = samples[x * height + y];
				Assert.assertEquals(x * xSpace, light.getLocation().getX(), 1e-12);
				Assert.assertEquals(y * ySpace, light.getLocation().getY(), 1e-12);
			}
		}

		Assert.assertEquals(width * height, positions.size());
		Assert.assertSame(samples, grid.getSamples(1));
		Assert.assertEquals(1.0, grid.getIntensity(), 1e-12);
	}

	/** A grid wider than it is tall has a light in every cell. */
	@Test(timeout = 30000)
	public void wideGridHasOneLightPerCell() {
		assertGrid(3, 2);
	}

	/** A grid taller than it is wide has a light in every cell. */
	@Test(timeout = 30000)
	public void tallGridHasOneLightPerCell() {
		assertGrid(2, 3);
	}

	/** A square grid, and the degenerate single-row and single-light grids. */
	@Test(timeout = 30000)
	public void squareAndDegenerateGrids() {
		assertGrid(3, 3);
		assertGrid(1, 4);
		assertGrid(4, 1);
		assertGrid(1, 1);
	}

	/**
	 * Rebuilding a grid from a prototype gives every light the intensity of the prototype and
	 * the colour of the grid, and the grid the sum of the intensities of its lights.
	 */
	@Test(timeout = 30000)
	public void updateGridCopiesPrototype() {
		int width = 3;
		int height = 2;
		PointLightGrid grid = new PointLightGrid(1, 1, 1.0, 1.0);
		grid.updateGrid(width, height, 1.0, 1.0, new PointLight(0.5, new RGB(0.2, 0.4, 0.6)));

		PointLight[] samples = grid.getSamples();
		Assert.assertEquals(width * height, samples.length);

		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				PointLight light = samples[x * height + y];
				Assert.assertEquals(x, light.getLocation().getX(), 1e-12);
				Assert.assertEquals(y, light.getLocation().getY(), 1e-12);
				Assert.assertEquals(0.5, light.getIntensity(), 1e-12);
				Assert.assertSame(grid.getColor(), light.getColor());
			}
		}

		Assert.assertEquals(0.5 * width * height, grid.getIntensity(), 1e-12);
	}

	/**
	 * A grid built from a coloured prototype takes the prototype colour rather than white,
	 * holds its own copy of it, and a grid built without a prototype is white.
	 */
	@Test(timeout = 30000)
	public void gridTakesPrototypeColor() {
		RGB prototypeColor = new RGB(0.2, 0.4, 0.6);
		PointLightGrid grid = new PointLightGrid(2, 3, 1.0, 1.0, new PointLight(0.5, prototypeColor));

		Assert.assertNotSame(prototypeColor, grid.getColor());
		Assert.assertEquals(0.2, grid.getColor().getRed(), 1e-6);
		Assert.assertEquals(0.4, grid.getColor().getGreen(), 1e-6);
		Assert.assertEquals(0.6, grid.getColor().getBlue(), 1e-6);

		for (PointLight light : grid.getSamples()) {
			Assert.assertSame(grid.getColor(), light.getColor());
		}

		prototypeColor.setRed(0.9);
		Assert.assertEquals(0.2, grid.getColor().getRed(), 1e-6);

		RGB white = new PointLightGrid(2, 2, 1.0, 1.0).getColor();
		Assert.assertEquals(1.0, white.getRed(), 1e-6);
		Assert.assertEquals(1.0, white.getGreen(), 1e-6);
		Assert.assertEquals(1.0, white.getBlue(), 1e-6);
	}

	/**
	 * The default lighting calculation of a {@link SurfaceLight} sums the contribution of
	 * every sample, and gives each sample its own copy of the context with that sample as the
	 * light, leaving the caller's context untouched.
	 */
	@Test(timeout = 60000)
	public void surfaceLightSumsSamples() {
		ConstantLight a = new ConstantLight(0.1, 0.2, 0.3);
		ConstantLight b = new ConstantLight(0.4, 0.0, 0.5);
		ConstantLight c = new ConstantLight(0.0, 0.25, 0.125);
		FixedSurfaceLight light = new FixedSurfaceLight(a, b, c);

		ShaderContext context = new ShaderContext(null, light);
		PackedCollection result = light.lightingCalculation(null, context).get().evaluate();

		Assert.assertEquals(0.5, result.toDouble(0), 1e-6);
		Assert.assertEquals(0.45, result.toDouble(1), 1e-6);
		Assert.assertEquals(0.925, result.toDouble(2), 1e-6);

		for (ConstantLight sample : List.of(a, b, c)) {
			Assert.assertEquals(1, sample.contexts.size());
			Assert.assertNotSame(context, sample.contexts.get(0));
			Assert.assertSame(sample, sample.contexts.get(0).getLight());
		}

		Assert.assertSame(light, context.getLight());
	}

	/** A {@link SurfaceLight} with no samples contributes black. */
	@Test(timeout = 60000)
	public void surfaceLightWithoutSamplesIsBlack() {
		FixedSurfaceLight light = new FixedSurfaceLight();
		PackedCollection result = light.lightingCalculation(null, new ShaderContext(null, light)).get().evaluate();

		for (int i = 0; i < 3; i++) {
			Assert.assertEquals(0.0, result.toDouble(i), 0.0);
		}
	}
}
