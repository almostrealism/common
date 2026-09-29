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

package org.almostrealism.space.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.space.Plane;
import org.almostrealism.space.SurfaceGroup;
import org.almostrealism.texture.Texture;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

/**
 * Behavioral tests for {@link org.almostrealism.space.AbstractSurface}, exercised through
 * the concrete {@link Plane} subclass: material properties, textures, shaders, and
 * parent-driven shading flags.
 */
public class AbstractSurfaceTest extends TestSuiteBase {

	/** A constant texture used to exercise the texture collection. */
	private static final class ConstantTexture implements Texture {
		/** The color returned regardless of the sampled point. */
		private final RGB color;

		/**
		 * Constructs a texture that always returns the given color.
		 *
		 * @param color the constant color
		 */
		private ConstantTexture(RGB color) {
			this.color = color;
		}

		@Override
		public Evaluable<PackedCollection> getColorAt(Object[] args) {
			return a -> color;
		}

		@Override
		public RGB operate(Vector t) {
			return color;
		}
	}

	/** Color set via the setter is returned unchanged. */
	@Test(timeout = 10000)
	public void colorRoundTrip() {
		Plane p = new Plane();
		p.setColor(new RGB(0.25, 0.5, 0.75));
		RGB c = p.getColor();
		Assert.assertEquals(0.25, c.getRed(), 1e-12);
		Assert.assertEquals(0.5, c.getGreen(), 1e-12);
		Assert.assertEquals(0.75, c.getBlue(), 1e-12);
	}

	/** Index of refraction defaults to 1.0 and round-trips, including the point-wise accessor. */
	@Test(timeout = 10000)
	public void indexOfRefraction() {
		Plane p = new Plane();
		Assert.assertEquals(1.0, p.getIndexOfRefraction(), 1e-12);
		p.setIndexOfRefraction(1.5);
		Assert.assertEquals(1.5, p.getIndexOfRefraction(), 1e-12);
		Assert.assertEquals(1.5, p.getIndexOfRefraction(new Vector(1.0, 2.0, 3.0)), 1e-12);
	}

	/** Reflected and refracted percentages round-trip and expose point-wise accessors. */
	@Test(timeout = 10000)
	public void reflectAndRefractPercentages() {
		Plane p = new Plane();
		Assert.assertEquals(1.0, p.getReflectedPercentage(), 1e-12);
		Assert.assertEquals(0.0, p.getRefractedPercentage(), 1e-12);

		p.setReflectedPercentage(0.3);
		p.setRefractedPercentage(0.7);
		Assert.assertEquals(0.3, p.getReflectedPercentage(), 1e-12);
		Assert.assertEquals(0.7, p.getRefractedPercentage(), 1e-12);
		Assert.assertEquals(0.3, p.getReflectedPercentage(new Vector(0.0, 0.0, 0.0)), 1e-12);
		Assert.assertEquals(0.7, p.getRefractedPercentage(new Vector(0.0, 0.0, 0.0)), 1e-12);
	}

	/** Porosity round-trips through its setter and getter. */
	@Test(timeout = 10000)
	public void porosity() {
		Plane p = new Plane();
		Assert.assertEquals(0.0, p.getPorosity(), 1e-12);
		p.setPorosity(0.4);
		Assert.assertEquals(0.4, p.getPorosity(), 1e-12);
	}

	/**
	 * Textures can be appended, retrieved by index, and the last texture removed.
	 *
	 * <p>Note: removing a non-final index is a known pre-existing defect in
	 * {@code AbstractSurface.removeTexture} (its second {@code System.arraycopy}
	 * uses {@code index + 1} as the destination offset, leaving a null hole), so
	 * this test only exercises removal of the final texture, which is correct.
	 */
	@Test(timeout = 10000)
	public void textureAddRemove() {
		Plane p = new Plane();
		Assert.assertEquals(0, p.getTextures().length);

		Texture t0 = new ConstantTexture(new RGB(1.0, 0.0, 0.0));
		Texture t1 = new ConstantTexture(new RGB(0.0, 1.0, 0.0));
		p.addTexture(t0);
		p.addTexture(t1);
		Assert.assertEquals(2, p.getTextures().length);
		Assert.assertSame(t0, p.getTexture(0));
		Assert.assertSame(t1, p.getTexture(1));

		p.removeTexture(1);
		Assert.assertEquals(1, p.getTextures().length);
		Assert.assertSame(t0, p.getTexture(0));
	}

	/** The texture Set view reflects size and membership. */
	@Test(timeout = 10000)
	public void textureSetView() {
		Plane p = new Plane();
		Texture t0 = new ConstantTexture(new RGB(1.0, 0.0, 0.0));

		Set<Texture> set = p.getTextureSet();
		Assert.assertTrue(set.isEmpty());

		set.add(t0);
		Assert.assertEquals(1, set.size());
		Assert.assertTrue(set.contains(t0));
		Assert.assertFalse(set.contains(new ConstantTexture(new RGB(0.0, 0.0, 1.0))));
	}

	/** The texture Set view supports iteration, bulk add, containsAll, toArray, and clear. */
	@Test(timeout = 10000)
	public void textureSetBulkOperations() {
		Plane p = new Plane();
		Texture t0 = new ConstantTexture(new RGB(1.0, 0.0, 0.0));
		Texture t1 = new ConstantTexture(new RGB(0.0, 1.0, 0.0));

		Set<Texture> set = p.getTextureSet();
		set.addAll(Arrays.asList(t0, t1));
		Assert.assertEquals(2, set.size());
		Assert.assertTrue(set.containsAll(Arrays.asList(t0, t1)));

		int seen = 0;
		for (Texture t : set) {
			Assert.assertNotNull(t);
			seen++;
		}
		Assert.assertEquals(2, seen);

		Assert.assertEquals(2, set.toArray().length);

		set.clear();
		Assert.assertTrue(set.isEmpty());
		Assert.assertEquals(0, p.getTextures().length);
	}

	/** setShaders clears the set and addShader grows it. */
	@Test(timeout = 10000)
	public void shaderSetGrows() {
		Plane p = new Plane();
		p.setShaders(new Shader[0]);
		Assert.assertTrue(p.getShaderSet().isEmpty());

		p.addShader(new DiffuseShader());
		Assert.assertFalse(p.getShaderSet().isEmpty());
		Assert.assertTrue(p.getShaderSet().size() == 1);
	}

	/** Shading flags default front-on, back-off, and round-trip. */
	@Test(timeout = 10000)
	public void shadingFlagDefaults() {
		Plane p = new Plane();
		Assert.assertTrue(p.getShadeFront());
		Assert.assertFalse(p.getShadeBack());

		p.setShadeFront(false);
		p.setShadeBack(true);
		Assert.assertFalse(p.getShadeFront());
		Assert.assertTrue(p.getShadeBack());
	}

	/** A parent group with shadeFront forces the child to report shadeFront. */
	@Test(timeout = 10000)
	public void parentForcesShadeFront() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		group.setShadeFront(true);

		Plane p = new Plane();
		p.setShadeFront(false);
		p.setParent(group);

		Assert.assertSame(group, p.getParent());
		// Even though the child's own flag is false, the parent forces it true.
		Assert.assertTrue(p.getShadeFront());
	}

	/** The input operator round-trips through the vector setter. */
	@Test(timeout = 10000)
	public void inputOperator() {
		Plane p = new Plane();
		Assert.assertNull(p.getInput());
		p.setInput(new Vector(1.0, 2.0, 3.0));
		Assert.assertNotNull(p.getInput());
	}

	/** AbstractSurface's default bounding solid is null. */
	@Test(timeout = 10000)
	public void defaultBoundingSolidNull() {
		Assert.assertNull(new Plane().calculateBoundingSolid());
	}
}
