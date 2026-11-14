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

package org.almostrealism.render.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.render.RayTracedAnimation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Tests {@link RayTracedAnimation}'s frame-sequence orchestration without the lit ray trace: a
 * subclass substitutes a deterministic stub for {@link RayTracedAnimation#renderFrame()}, so the
 * frame counting, naming and encode-script output are exercised while the known-issue lighting
 * render path is not.
 *
 * <p>The focus is the one-shot-to-reusable fix: {@link RayTracedAnimation#run()} resets the frame
 * sequence and frame counter so a second run of the same animation does not overflow the sequence's
 * fixed frame count.</p>
 */
public class RayTracedAnimationTest extends TestSuiteBase {
	/** Physics iterations per run. */
	private static final int ITERATIONS = 6;

	/** Physics ticks per rendered frame, so each run writes {@code ITERATIONS / ITERATIONS_PER_FRAME} frames. */
	private static final int ITERATIONS_PER_FRAME = 2;

	/** Rendered frames per run, derived from {@link #ITERATIONS} and {@link #ITERATIONS_PER_FRAME}. */
	private static final int FRAMES = ITERATIONS / ITERATIONS_PER_FRAME;

	/** The instance identifier used for the frame-file prefix. */
	private static final String INSTANCE = "0";

	/**
	 * A single run writes exactly {@link RayTracedAnimation#getFrameCount()} frames, named in frame
	 * order behind the instance prefix, renders the last frame, and writes an encode script.
	 */
	@Test(timeout = 120000)
	public void rendersConfiguredFrameCount() throws IOException {
		File dir = Files.createTempDirectory("raytraced-animation").toFile();
		RayTracedAnimation<ShadableSurface> animation = stubbedAnimation(dir);

		Assert.assertEquals(FRAMES, animation.getFrameCount());

		animation.run();

		assertFramesPresent(dir);
		Assert.assertTrue("encode script was not written", new File(dir, "encode.sh").isFile());
		Assert.assertNotNull("no frame was rendered", animation.getImage());
	}

	/**
	 * Running the same animation a second time renders another full sequence instead of overflowing
	 * the frame sequence. The sequence's frame count is fixed on the first run, so without resetting
	 * the writer and frame counter the second run would start at index {@link #FRAMES} and
	 * {@code ImageSequence.writeFrame} would throw {@link IndexOutOfBoundsException}.
	 */
	@Test(timeout = 120000)
	public void reusedAnimationRendersAgainWithoutOverflow() throws IOException {
		File dir = Files.createTempDirectory("raytraced-animation-reuse").toFile();
		RayTracedAnimation<ShadableSurface> animation = stubbedAnimation(dir);

		animation.run();
		assertFramesPresent(dir);

		// A second run must not throw IndexOutOfBoundsException and must leave the full sequence present
		animation.run();
		assertFramesPresent(dir);
		Assert.assertTrue("encode script was not written", new File(dir, "encode.sh").isFile());
	}

	/**
	 * Asserts that every frame of a completed run is present on disk, named {@code frame_<instance>.<i>.png}.
	 *
	 * @param dir the output directory
	 */
	private static void assertFramesPresent(File dir) {
		for (int i = 0; i < FRAMES; i++) {
			File frame = new File(dir, "frame_" + INSTANCE + "." + i + ".png");
			Assert.assertTrue("frame " + i + " was not written", frame.isFile());
			Assert.assertTrue("frame " + i + " is empty", frame.length() > 0);
		}
	}

	/**
	 * Builds a configured {@link RayTracedAnimation} whose render step is a deterministic stub, writing
	 * into the given output directory.
	 *
	 * @param dir the output directory
	 * @return the configured animation
	 */
	private RayTracedAnimation<ShadableSurface> stubbedAnimation(File dir) {
		RayTracedAnimation<ShadableSurface> animation = new RayTracedAnimation<>() {
			{
				// setTickDuration is protected, so it is set from within the subclass
				setTickDuration(1.0);
			}

			@Override
			protected RGB[][] renderFrame() {
				return new RGB[][] { { new RGB(0.5, 0.5, 0.5) } };
			}
		};

		animation.setImageDimensions(1, 1);
		animation.setIterations(ITERATIONS);
		animation.setFPS(1.0 / ITERATIONS_PER_FRAME);
		animation.setRenderEachFrame(true);
		animation.setOutputDirectory(dir.getAbsolutePath() + File.separator);

		PinholeCamera camera = new PinholeCamera();
		camera.setLocation(new Vector(0.0, 0.0, 10.0));
		camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
		camera.setProjectionDimensions(0.04, 0.04);
		animation.setCamera(camera);

		return animation;
	}
}
