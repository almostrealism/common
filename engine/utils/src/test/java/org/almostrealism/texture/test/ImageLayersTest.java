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

import org.almostrealism.texture.ImageLayers;
import org.almostrealism.texture.ImageSource;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Tests for {@link ImageLayers}, a named collection of {@link ImageSource} layers that
 * delegates its pixel/dimension queries to an arbitrary member layer.
 */
public class ImageLayersTest extends TestSuiteBase {
	/**
	 * A fixed, in-memory {@link ImageSource} used to populate layers.
	 */
	private static class FixedSource implements ImageSource {
		/** The flat pixel array returned by this source. */
		private final int[] pixels;
		/** The image width. */
		private final int width;
		/** The image height. */
		private final int height;
		/** Whether the source reports an alpha channel. */
		private final boolean alpha;

		/**
		 * Constructs a fixed image source.
		 *
		 * @param pixels the pixel array
		 * @param width the image width
		 * @param height the image height
		 * @param alpha whether the source reports an alpha channel
		 */
		FixedSource(int[] pixels, int width, int height, boolean alpha) {
			this.pixels = pixels;
			this.width = width;
			this.height = height;
			this.alpha = alpha;
		}

		@Override
		public int[] getPixels() {
			return pixels;
		}

		@Override
		public int getWidth() {
			return width;
		}

		@Override
		public int getHeight() {
			return height;
		}

		@Override
		public boolean isAlpha() {
			return alpha;
		}
	}

	/**
	 * Verifies a layer added by name is retrievable and that querying an absent
	 * name returns {@code null}.
	 */
	@Test(timeout = 30000)
	public void layerAddAndLookup() {
		ImageLayers layers = new ImageLayers();
		FixedSource source = new FixedSource(new int[] {1, 2, 3, 4}, 2, 2, true);

		layers.addLayer("base", source);
		Assert.assertSame(source, layers.getLayer("base"));
		Assert.assertNull(layers.getLayer("missing"));
	}

	/**
	 * Verifies the delegating queries ({@code getPixels}, {@code getWidth},
	 * {@code getHeight}, {@code isAlpha}) forward to the single member layer.
	 */
	@Test(timeout = 30000)
	public void queriesDelegateToSingleLayer() {
		ImageLayers layers = new ImageLayers();
		int[] px = new int[] {10, 20, 30, 40, 50, 60};
		layers.addLayer("only", new FixedSource(px, 3, 2, true));

		Assert.assertArrayEquals(px, layers.getPixels());
		Assert.assertEquals(3, layers.getWidth());
		Assert.assertEquals(2, layers.getHeight());
		Assert.assertTrue(layers.isAlpha());
	}

	/**
	 * Verifies {@link ImageLayers#addLayers(ImageLayers)} merges every named layer
	 * from another instance.
	 */
	@Test(timeout = 30000)
	public void addLayersMergesNamedLayers() {
		ImageLayers source = new ImageLayers();
		FixedSource a = new FixedSource(new int[] {1}, 1, 1, false);
		FixedSource b = new FixedSource(new int[] {2}, 1, 1, false);
		source.addLayer("a", a);
		source.addLayer("b", b);

		ImageLayers target = new ImageLayers();
		target.addLayers(source);

		Assert.assertSame(a, target.getLayer("a"));
		Assert.assertSame(b, target.getLayer("b"));
	}

	/**
	 * Verifies the iterator visits every layer exactly once.
	 */
	@Test(timeout = 30000)
	public void iteratorVisitsAllLayers() {
		ImageLayers layers = new ImageLayers();
		layers.addLayer("a", new FixedSource(new int[] {1}, 1, 1, false));
		layers.addLayer("b", new FixedSource(new int[] {2}, 1, 1, false));

		int count = 0;
		for (Iterator<ImageSource> it = layers.iterator(); it.hasNext(); ) {
			it.next();
			count++;
		}

		Assert.assertEquals(2, count);
	}

	/**
	 * Verifies {@link ImageLayers#clear()} removes all layers, after which a named
	 * lookup returns {@code null} and a delegating query has no layer to forward to.
	 */
	@Test(timeout = 30000)
	public void clearRemovesAllLayers() {
		ImageLayers layers = new ImageLayers();
		layers.addLayer("only", new FixedSource(new int[] {1}, 1, 1, false));
		layers.clear();

		Assert.assertNull(layers.getLayer("only"));
		Assert.assertFalse(layers.iterator().hasNext());

		boolean threw = false;
		try {
			layers.getPixels();
		} catch (NoSuchElementException e) {
			threw = true;
		}
		Assert.assertTrue("expected NoSuchElementException on empty layers", threw);
	}
}
