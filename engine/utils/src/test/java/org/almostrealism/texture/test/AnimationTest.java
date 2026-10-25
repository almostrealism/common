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

import org.almostrealism.algebra.Pair;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RealizableImage;
import org.almostrealism.texture.Animation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Iterator;

/**
 * Tests for {@link Animation}, the abstract frame sequence whose iterator advances
 * indefinitely and whose {@link Animation#next()} hook supplies each frame's filename.
 */
public class AnimationTest extends TestSuiteBase {
	/**
	 * A concrete {@link Animation} that names frames sequentially and counts how many
	 * times {@link #next()} has been invoked.
	 */
	private static class CountingAnimation extends Animation {
		/** The number of times {@link #next()} has been invoked. */
		private int frame;

		/**
		 * Constructs a counting animation.
		 *
		 * @param image the frame image
		 */
		CountingAnimation(RealizableImage image) {
			super(image);
		}

		/** Constructs a counting animation with no initial frame image. */
		CountingAnimation() {
			super();
		}

		@Override
		public String next() {
			return "frame-" + (frame++) + ".jpg";
		}

		/**
		 * Returns how many frames have been advanced.
		 *
		 * @return the frame count
		 */
		int frameCount() {
			return frame;
		}
	}

	/**
	 * Builds a trivial 1x1 image for the animation to return.
	 *
	 * @return a realizable image
	 */
	private RealizableImage image() {
		return new RealizableImage(p -> new RGB(0.0, 0.0, 0.0), new Pair(1, 1));
	}

	/**
	 * Verifies the iterator is unbounded and each {@link Iterator#next()} advances the
	 * underlying frame hook while returning the current frame image.
	 */
	@Test(timeout = 30000)
	public void iteratorAdvancesFrames() {
		RealizableImage img = image();
		CountingAnimation animation = new CountingAnimation(img);
		Iterator<RealizableImage> it = animation.iterator();

		Assert.assertTrue(it.hasNext());
		Assert.assertSame(img, it.next());
		Assert.assertTrue(it.hasNext());
		Assert.assertSame(img, it.next());

		Assert.assertEquals(2, animation.frameCount());
	}

	/**
	 * Verifies {@link Animation#setImage(RealizableImage)} replaces the frame image
	 * returned by the iterator.
	 */
	@Test(timeout = 30000)
	public void setImageReplacesFrame() {
		CountingAnimation animation = new CountingAnimation(image());
		RealizableImage replacement = image();
		animation.setImage(replacement);

		Assert.assertSame(replacement, animation.iterator().next());
	}

	/**
	 * Verifies the no-argument constructor leaves the frame image unset (the iterator
	 * yields {@code null}) until {@link Animation#setImage(RealizableImage)} supplies
	 * one, while still advancing the frame hook.
	 */
	@Test(timeout = 30000)
	public void noArgConstructorHasNoImageUntilSet() {
		CountingAnimation animation = new CountingAnimation();
		Iterator<RealizableImage> it = animation.iterator();

		Assert.assertNull(it.next());
		Assert.assertEquals(1, animation.frameCount());

		RealizableImage img = image();
		animation.setImage(img);
		Assert.assertSame(img, it.next());
	}

	/**
	 * Verifies {@link Animation#render()} returns a rendering thread that has not yet
	 * been started.
	 */
	@Test(timeout = 30000)
	public void renderReturnsUnstartedThread() {
		CountingAnimation animation = new CountingAnimation(image());
		Thread thread = animation.render();

		Assert.assertNotNull(thread);
		Assert.assertFalse(thread.isAlive());
	}
}
