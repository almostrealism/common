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
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.SurfaceLight;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the default {@link SurfaceLight#lightingCalculation(ShadableSurface, ShaderContext)},
 * which sums the contributions of the samples of a {@link SurfaceLight}. The grid of lights a
 * {@code PointLightGrid} supplies as its samples is tested by
 * {@code org.almostrealism.primitives.test.PointLightGridTest} in {@code engine/utils}.
 */
public class SurfaceLightTest extends TestSuiteBase implements RGBFeatures {

	/**
	 * A {@link Light} whose contribution is a constant colour, recording each context it is
	 * asked to light so that the per-sample context handling can be checked.
	 */
	private static final class ConstantLight implements Light, RGBFeatures {
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
			return value(color);
		}

		@Override
		public Producer<PackedCollection> lightingCalculation(ShadableSurface surface, ShaderContext context) {
			contexts.add(context);
			return value(color);
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
