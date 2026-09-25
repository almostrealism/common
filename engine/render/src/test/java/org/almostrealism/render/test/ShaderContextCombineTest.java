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
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.Curve;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins the behavior of {@link ShaderContext#getAllSurfaces()} and
 * {@link org.almostrealism.color.LightingContext#getAllLights()}, the shared
 * implementations that {@code ReflectionShader} now delegates to for both
 * surface and light assembly, and that {@code RefractionShader} delegates to
 * for light assembly only — {@code RefractionShader} still assembles its
 * surface candidate list locally, in primary-last order, because its tie-break
 * requirement is the opposite of what {@code getAllSurfaces()} provides (see
 * {@code RefractionShader#shade(Vector, Vector, Producer, Light, Iterable,
 * Curve, Curve[], Vector, ShaderContext)} for why).
 *
 * <p>The manual copies these tests replaced added the primary surface/light
 * first, followed by the other surfaces/lights, so these tests assert that
 * ordering for {@link ShaderContext#getAllSurfaces()} and
 * {@code getAllLights()}. They also assert that {@link ShaderContext#getAllSurfaces()}
 * omits a {@code null} primary surface — a null-safety guard the former manual
 * surface loop in {@code ReflectionShader} lacked.</p>
 */
public class ShaderContextCombineTest extends TestSuiteBase {

	/**
	 * A minimal {@link Curve} used only for identity and ordering checks; its
	 * spatial value and normal are irrelevant to list assembly.
	 */
	private static final class NamedCurve implements Curve<PackedCollection> {
		/** Identifier used only to distinguish instances in assertion messages. */
		private final String name;

		private NamedCurve(String name) { this.name = name; }

		@Override
		public Producer<PackedCollection> getValueAt(Producer<PackedCollection> point) { return null; }

		@Override
		public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> point) { return null; }

		@Override
		public String toString() { return "NamedCurve(" + name + ")"; }
	}

	/**
	 * Verifies that {@link org.almostrealism.color.LightingContext#getAllLights()}
	 * returns the primary light first, followed by the other lights in order.
	 */
	@Test(timeout = 5000)
	public void getAllLightsOrdersPrimaryFirst() {
		PointLight primary = new PointLight();
		PointLight other0 = new PointLight();
		PointLight other1 = new PointLight();

		ShaderContext context = new ShaderContext(new NamedCurve("s"), primary);
		List<Light> others = new ArrayList<>();
		others.add(other0);
		others.add(other1);
		context.setOtherLights(others);

		List<Light> all = context.getAllLights();
		Assert.assertEquals(3, all.size());
		Assert.assertSame(primary, all.get(0));
		Assert.assertSame(other0, all.get(1));
		Assert.assertSame(other1, all.get(2));
	}

	/**
	 * Verifies that {@link org.almostrealism.color.LightingContext#getAllLights()}
	 * matches the manual list-assembly idiom the shaders used before
	 * consolidation (primary light, then each other light).
	 */
	@Test(timeout = 5000)
	public void getAllLightsMatchesLegacyManualCombination() {
		PointLight primary = new PointLight();
		PointLight other0 = new PointLight();

		ShaderContext context = new ShaderContext(new NamedCurve("s"), primary);
		List<Light> others = new ArrayList<>();
		others.add(other0);
		context.setOtherLights(others);

		List<Light> manual = new ArrayList<>();
		manual.add(context.getLight());
		for (Light l : context.getOtherLights()) { manual.add(l); }

		Assert.assertEquals(manual, context.getAllLights());
	}

	/**
	 * Verifies that {@link ShaderContext#getAllSurfaces()} returns the primary
	 * surface first, followed by the other surfaces in order.
	 */
	@Test(timeout = 5000)
	public void getAllSurfacesOrdersPrimaryFirst() {
		NamedCurve primary = new NamedCurve("primary");
		NamedCurve other0 = new NamedCurve("other0");
		NamedCurve other1 = new NamedCurve("other1");

		ShaderContext context = new ShaderContext(primary, new PointLight());
		context.setOtherSurfaces(other0, other1);

		List<Curve<PackedCollection>> all = new ArrayList<>();
		for (Curve<PackedCollection> s : context.getAllSurfaces()) { all.add(s); }

		Assert.assertEquals(3, all.size());
		Assert.assertSame(primary, all.get(0));
		Assert.assertSame(other0, all.get(1));
		Assert.assertSame(other1, all.get(2));
	}

	/**
	 * Verifies that {@link ShaderContext#getAllSurfaces()} omits a {@code null}
	 * primary surface rather than including {@code null} in the returned list.
	 * The manual surface loop that {@code ReflectionShader} previously used added
	 * the primary surface unconditionally; consolidating onto
	 * {@link ShaderContext#getAllSurfaces()} adopts this safer behavior.
	 */
	@Test(timeout = 5000)
	public void getAllSurfacesSkipsNullPrimary() {
		NamedCurve other0 = new NamedCurve("other0");

		ShaderContext context = new ShaderContext(null, new PointLight());
		context.setOtherSurfaces(other0);

		List<Curve<PackedCollection>> all = new ArrayList<>();
		for (Curve<PackedCollection> s : context.getAllSurfaces()) { all.add(s); }

		Assert.assertEquals(1, all.size());
		Assert.assertSame(other0, all.get(0));
	}

	/**
	 * Verifies that {@link ShaderContext#getAllSurfaces()} returns a new list on
	 * each call, so a caller that mutates the returned list (as a shader may when
	 * it hands the list to a lighting aggregator) never alters the context's own
	 * surfaces or a list returned to another caller.
	 */
	@Test(timeout = 5000)
	public void getAllSurfacesReturnsIndependentList() {
		NamedCurve primary = new NamedCurve("primary");
		NamedCurve other0 = new NamedCurve("other0");

		ShaderContext context = new ShaderContext(primary, new PointLight());
		context.setOtherSurfaces(other0);

		List<Curve<PackedCollection>> first = context.getAllSurfaces();
		first.clear();
		first.add(new NamedCurve("extra"));

		List<Curve<PackedCollection>> second = context.getAllSurfaces();
		Assert.assertNotSame(first, second);
		Assert.assertEquals(2, second.size());
		Assert.assertSame(primary, second.get(0));
		Assert.assertSame(other0, second.get(1));
		Assert.assertEquals(1, context.getOtherSurfaces().length);
		Assert.assertSame(other0, context.getOtherSurfaces()[0]);
	}
}
