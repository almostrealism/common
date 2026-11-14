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

package org.almostrealism.render;

import org.almostrealism.color.RGB;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.space.Scene;
import org.almostrealism.texture.ImageSequence;
import org.almostrealism.time.SimulationRunner;

import java.io.File;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Renders a frame sequence from a running simulation by ray tracing, without the frame loop owning
 * the physics.
 *
 * <p>The physics is stepped only by a {@link SimulationRunner}: this driver hangs off the runner's
 * {@link SimulationRunner#runFrames(int, int, java.util.function.IntConsumer) frame driver}, so the
 * runner remains the sole owner of the step loop. At each frame boundary a caller-supplied hook
 * rebuilds the renderable {@link Scene} from the current simulation state (for example a mesh
 * reconstructed from a height field), the scene is ray traced, and the frame is handed to an
 * {@link ImageSequence}, which owns the frame-sequence output: the output directory, the zero-padded
 * frame naming, tracking of written frames, the PNG encode, and the {@code ffmpeg} encode script
 * that matches the frames on disk.</p>
 *
 * <p>The outer frame loop therefore does not drive the physics step — it only observes the state the
 * runner produces, as the simulation-loop contract requires.</p>
 *
 * <h2>Why this is not a subclass or superclass of {@link RayTracedAnimation}</h2>
 *
 * <p>Both this type and {@link RayTracedAnimation} end in a ray-traced frame sequence on disk, so it
 * is tempting to make one inherit the other. They are, however, not related by specialization in
 * either direction; the only thing they share is the frame-output <em>collaborator</em>, and both
 * already hold one (composition), which is the correct resolution rather than an {@code IS-A} chain.
 * The defense, concretely:</p>
 *
 * <ul>
 * <li><b>Loop ownership is the decisive, opposing axis.</b> {@link RayTracedAnimation} extends
 * {@link org.almostrealism.space.Animation Animation}, whose {@link org.almostrealism.space.Animation#run() run()}
 * <em>steps the physics itself</em> and calls back at frame boundaries — the animation owns its clock.
 * This driver deliberately owns no loop: it hangs off {@link SimulationRunner#runFrames}, leaving the
 * {@link SimulationRunner} the sole owner of the step loop, which is the whole point of the
 * simulation-loop contract. If this class inherited {@code Animation.run()} it would reintroduce the
 * self-driven physics loop that contract forbids. The two sit on opposite sides of the
 * loop-ownership boundary, not on a common "kind of frame renderer" specialization axis.</li>
 *
 * <li><b>Neither is substitutable for the other (Liskov).</b> An {@code Animation} <em>is</em> a
 * {@link org.almostrealism.space.Scene Scene}: it holds the rigid bodies and camera, serializes and
 * restores its own state as {@link java.util.Properties}, and renders <em>itself</em>
 * ({@code new RayTracedScene(this, ...)}). This driver holds no scene and has no such state — it is
 * handed a <em>different</em> scene for every frame by the caller's {@code sceneForFrame} hook (for
 * example a mesh reconstructed from a height field that did not exist when the driver was built).
 * Code that holds an {@code Animation} (steps it, serializes it, adds bodies) cannot accept this
 * driver, and code that holds this driver (a runner, a steps-per-frame count, a per-frame scene hook)
 * cannot accept an {@code Animation}.</li>
 *
 * <li><b>The superclass direction is also mechanically closed.</b> {@code RayTracedAnimation} already
 * extends {@code Animation}, so under single inheritance it cannot additionally extend this type; and
 * inserting this type <em>above</em> {@code Animation} is impossible, because an {@code Animation} has
 * neither a {@link SimulationRunner} nor a per-frame scene hook to supply.</li>
 *
 * <li><b>A shared superclass would be a forced, empty abstraction.</b> The only member the two have
 * in common is "holds an {@link ImageSequence} and exposes a render-frame template hook" — already
 * expressed by both composing an {@code ImageSequence}. A base class holding only that one field could
 * not host anything that actually differs (loop ownership, scene source, frame indexing from an
 * iteration counter versus a runner's frame index, {@code Properties} lifecycle versus a stateless
 * binding), so it would exist solely to force a hierarchy around a single field — the very shape the
 * rectangle/square cautionary tale warns against. No client consumes "either animation or driver"
 * polymorphically, so there is nothing for such a supertype to serve.</li>
 * </ul>
 *
 * @author Michael Murray
 * @see SimulationRunner#runFrames(int, int, java.util.function.IntConsumer)
 * @see ImageSequence
 * @see RayTracedScene#render()
 * @see RayTracedAnimation
 */
public class RayTracedFrameSequence implements ConsoleFeatures {
	/** The runner that owns the physics step loop. */
	private final SimulationRunner runner;

	/** The number of simulation steps between rendered frames. */
	private final int stepsPerFrame;

	/** The number of frames to render. */
	private final int frameCount;

	/** The hook that rebuilds the renderable scene from the simulation state for a frame index. */
	private final IntFunction<Scene<? extends ShadableSurface>> sceneForFrame;

	/** The render parameters (resolution, supersampling) applied to every frame. */
	private final RenderParameters params;

	/** The frame-sequence output: naming, tracking, encode and encode script. */
	private final ImageSequence sequence;

	/**
	 * Creates a driver that renders {@code frameCount} frames of the given simulation.
	 *
	 * @param runner          the runner that owns the physics step loop
	 * @param stepsPerFrame   the number of steps between rendered frames; must be positive
	 * @param frameCount      the number of frames to render; must not be negative
	 * @param sceneForFrame   the hook that rebuilds the renderable scene from the simulation state,
	 *                        called with the frame index; the scene it returns carries the camera
	 * @param params          the render parameters (resolution, supersampling) for every frame
	 * @param outputDirectory the directory the frames and encode script are written to
	 * @throws IllegalArgumentException if {@code stepsPerFrame} is not positive or {@code frameCount}
	 *                                  is negative
	 */
	public RayTracedFrameSequence(SimulationRunner runner, int stepsPerFrame, int frameCount,
								  IntFunction<Scene<? extends ShadableSurface>> sceneForFrame,
								  RenderParameters params, File outputDirectory) {
		if (stepsPerFrame <= 0) {
			throw new IllegalArgumentException("stepsPerFrame must be positive (" + stepsPerFrame + ")");
		}

		this.runner = runner;
		this.stepsPerFrame = stepsPerFrame;
		this.frameCount = frameCount;
		this.sceneForFrame = sceneForFrame;
		this.params = params;
		this.sequence = new ImageSequence(outputDirectory, frameCount);
	}

	/**
	 * Sets the frame rate written into the encode script.
	 *
	 * @param frameRate the frames per second; must be positive
	 * @throws IllegalArgumentException if {@code frameRate} is not positive
	 */
	public void setFrameRate(int frameRate) { sequence.setFrameRate(frameRate); }

	/** Returns the frame rate written into the encode script. */
	public int getFrameRate() { return sequence.getFrameRate(); }

	/**
	 * Returns the frames written so far, in order.
	 *
	 * @return the frame files
	 */
	public List<File> getFrameFiles() { return sequence.getFrameFiles(); }

	/**
	 * Drives the simulation into the frame sequence: the runner steps the physics and this driver
	 * renders a frame at each frame boundary, then writes the encode script. The runner should not
	 * have been advanced before this call, since frame indices are derived from its step counter.
	 *
	 * @throws IllegalStateException if the output directory does not exist and cannot be created
	 */
	public void run() {
		sequence.prepare();
		runner.runFrames(stepsPerFrame, frameCount, this::renderFrame);
		sequence.writeEncodeScript();
	}

	/**
	 * Rebuilds the scene from the current simulation state, renders it, and hands the frame to the
	 * {@link ImageSequence}, which writes and tracks it.
	 *
	 * @param frameIndex the index of the frame to render
	 */
	protected void renderFrame(int frameIndex) {
		sequence.writeFrame(frameIndex, renderScene(sceneForFrame.apply(frameIndex)));
	}

	/**
	 * Ray traces the given scene with this driver's render parameters, returning the frame's pixels.
	 * This is the render step of a frame; it is the template-method hook a subclass overrides to
	 * render with a different engine or to post-process the frame. The surrounding naming, tracking,
	 * encode and encode script are the {@link ImageSequence}'s and are independent of it.
	 *
	 * @param scene the scene reconstructed from the simulation state
	 * @return the rendered pixels, indexed by column then row
	 */
	protected RGB[][] renderScene(Scene<? extends ShadableSurface> scene) {
		return new RayTracedScene(scene, new FogParameters(), params).render();
	}
}
