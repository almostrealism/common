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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.render.RayTracedFrameSequence;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.Scene;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Tests {@link RayTracedFrameSequence}: a coupled toy simulation driven by {@link SimulationRunner}
 * renders exactly {@code frameCount} zero-padded frames, in order, with the runner advancing the
 * state and nothing else; an encode script whose frame list matches is written; and a second
 * identical run produces byte-identical frames.
 *
 * <p>The orchestration tests substitute a deterministic render step for the lit ray trace, which
 * exercises the frame driving, naming, encode script and determinism without the lighting-aggregator
 * render path that is a known issue in the default lanes. {@link #rendersWithRayTracer()} drives the
 * real ray tracer and is marked a known issue until that path is fixed.</p>
 */
public class RayTracedFrameSequenceTest extends TestSuiteBase {
	/** Step size for the toy simulation. */
	private static final double DT = 1e-3;

	/** The number of frames rendered by the tests. */
	private static final int FRAMES = 3;

	/** The number of simulation steps between frames. */
	private static final int STEPS_PER_FRAME = 8;

	/**
	 * The driver writes exactly {@code FRAMES} frames named in frame order, advances the runner by
	 * {@code FRAMES * STEPS_PER_FRAME} steps, and writes an {@code encode.sh} whose {@code ffmpeg}
	 * input pattern and frame count match the frames on disk.
	 */
	@Test(timeout = 120000)
	public void rendersFrameSequence() throws IOException {
		PackedCollection state = pack(1.0, 0.0).reshape(shape(2));
		SimulationRunner runner = new SimulationRunner(mixing(state), DT);
		File dir = Files.createTempDirectory("frame-sequence-test").toFile();

		RayTracedFrameSequence driver = stubbedDriver(runner, state, dir);
		driver.run();

		assertEquals((long) FRAMES * STEPS_PER_FRAME, runner.getStepCount());

		List<File> frames = driver.getFrameFiles();
		Assert.assertEquals(FRAMES, frames.size());
		for (int i = 0; i < FRAMES; i++) {
			File expected = new File(dir, "frame_" + i + ".png");
			Assert.assertEquals(expected, frames.get(i));
			Assert.assertTrue("frame " + i + " was not written", expected.isFile());
			Assert.assertTrue("frame " + i + " is empty", expected.length() > 0);
		}

		File encode = new File(dir, "encode.sh");
		Assert.assertTrue("encode script was not written", encode.isFile());
		String script = new String(Files.readAllBytes(encode.toPath()), StandardCharsets.UTF_8);
		Assert.assertTrue("encode script does not reference the frame pattern",
				script.contains("frame_%01d.png"));
		Assert.assertTrue("encode script frame count does not match",
				script.contains("-frames:v " + FRAMES));

		runner.destroy();
	}

	/**
	 * The simulation is advanced only by the runner: after the driver runs, the state equals that of
	 * a reference run of the same number of steps with no frame hook, so the per-frame observation
	 * does not perturb the trajectory.
	 */
	@Test(timeout = 120000)
	public void advancesOnlyViaRunner() throws IOException {
		PackedCollection state = pack(1.0, 0.0).reshape(shape(2));
		SimulationRunner runner = new SimulationRunner(mixing(state), DT);
		File dir = Files.createTempDirectory("frame-sequence-advance").toFile();
		stubbedDriver(runner, state, dir).run();

		PackedCollection reference = pack(1.0, 0.0).reshape(shape(2));
		SimulationRunner referenceRunner = new SimulationRunner(mixing(reference), DT);
		referenceRunner.run((long) FRAMES * STEPS_PER_FRAME);

		assertEquals(reference.toDouble(0), state.toDouble(0), 1e-9);
		assertEquals(reference.toDouble(1), state.toDouble(1), 1e-9);

		runner.destroy();
		referenceRunner.destroy();
	}

	/**
	 * Two runs with identical initial conditions on the same backend produce byte-identical frames,
	 * since the simulation and the scene reconstructed from its state are deterministic.
	 */
	@Test(timeout = 120000)
	public void runsAreReproducible() throws IOException {
		File first = renderInto("frame-sequence-a");
		File second = renderInto("frame-sequence-b");

		for (int i = 0; i < FRAMES; i++) {
			byte[] a = Files.readAllBytes(new File(first, "frame_" + i + ".png").toPath());
			byte[] b = Files.readAllBytes(new File(second, "frame_" + i + ".png").toPath());
			Assert.assertArrayEquals("frame " + i + " differs between runs", a, b);
		}
	}

	/**
	 * Drives the real ray tracer end to end. It exercises the same frame orchestration against a
	 * lit sphere scene, and is a known issue until the lighting-aggregator render path works in the
	 * default lanes (the single-pixel render is likewise a known issue).
	 */
	@TestProperties(knownIssue = true)
	@Test(timeout = 180000)
	public void rendersWithRayTracer() throws IOException {
		PackedCollection state = pack(1.0, 0.0).reshape(shape(2));
		SimulationRunner runner = new SimulationRunner(mixing(state), DT);
		File dir = Files.createTempDirectory("frame-sequence-raytrace").toFile();

		RayTracedFrameSequence driver = new RayTracedFrameSequence(runner, STEPS_PER_FRAME, FRAMES,
				sceneForFrame(state), new RenderParameters(4, 4, 1, 1), dir);
		driver.run();

		Assert.assertEquals(FRAMES, driver.getFrameFiles().size());
		runner.destroy();
	}

	/**
	 * Renders a frame sequence from a fresh identical simulation into a new temporary directory,
	 * using the deterministic stub render step.
	 *
	 * @param prefix the temporary directory name prefix
	 * @return the directory the frames were written to
	 * @throws IOException if the directory cannot be created
	 */
	private File renderInto(String prefix) throws IOException {
		PackedCollection state = pack(1.0, 0.0).reshape(shape(2));
		SimulationRunner runner = new SimulationRunner(mixing(state), DT);
		File dir = Files.createTempDirectory(prefix).toFile();

		stubbedDriver(runner, state, dir).run();
		runner.destroy();
		return dir;
	}

	/**
	 * Builds a driver whose render step writes a deterministic marker derived from the simulation
	 * state instead of ray tracing, so the frame-sequence orchestration can be tested without the
	 * lit render path.
	 *
	 * @param runner the runner
	 * @param state  the simulation state, read for the marker
	 * @param dir    the output directory
	 * @return the driver
	 */
	private RayTracedFrameSequence stubbedDriver(SimulationRunner runner, PackedCollection state, File dir) {
		return new RayTracedFrameSequence(runner, STEPS_PER_FRAME, FRAMES, sceneForFrame(state),
				new RenderParameters(4, 4, 1, 1), dir) {
			@Override
			protected void renderScene(Scene<? extends ShadableSurface> scene, File file) throws IOException {
				Files.write(file.toPath(),
						(state.toDouble(0) + "," + state.toDouble(1)).getBytes(StandardCharsets.UTF_8));
			}
		};
	}

	/**
	 * A two-element coupled mixing step {@code x <- M x}, so each element reads the other (the step
	 * is coupled, not element-local).
	 *
	 * @param state the state vector, shape {@code (2)}
	 * @return the mixing system
	 */
	private Temporal mixing(PackedCollection state) {
		PackedCollection operator = pack(0.9, 0.1, 0.1, 0.9).reshape(shape(2, 2));
		return coupledMatmulStep("Mixing", operator, state);
	}

	/**
	 * Builds the per-frame scene hook: a lit sphere whose radius is read from the current simulation
	 * state, so the scene the driver renders is reconstructed from the state at each frame boundary.
	 *
	 * @param state the simulation state
	 * @return the scene hook
	 */
	private IntFunction<Scene<? extends ShadableSurface>> sceneForFrame(PackedCollection state) {
		return frameIndex -> {
			Sphere sphere = new Sphere();
			sphere.setLocation(new Vector(0.0, 0.0, 0.0));
			sphere.setSize(0.5 + 0.5 * state.toDouble(0));
			sphere.setColor(new RGB(0.8, 0.2, 0.2));
			((AbstractSurface) sphere).setShaders(new Shader[] { DiffuseShader.defaultDiffuseShader });
			sphere.calculateTransform();

			PointLight light = new PointLight(new Vector(5.0, 5.0, 10.0));
			light.setColor(new RGB(1.0, 1.0, 1.0));
			light.setIntensity(1.0);

			PinholeCamera camera = new PinholeCamera();
			camera.setLocation(new Vector(0.0, 0.0, 10.0));
			camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
			camera.setFocalLength(0.05);
			camera.setProjectionDimensions(0.04, 0.04);

			Scene<ShadableSurface> scene = new Scene<>();
			scene.add(sphere);
			scene.addLight(light);
			scene.setCamera(camera);
			return scene;
		};
	}
}
