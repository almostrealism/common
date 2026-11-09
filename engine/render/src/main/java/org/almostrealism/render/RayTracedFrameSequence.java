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

import org.almostrealism.color.ShadableSurface;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.space.Scene;
import org.almostrealism.time.SimulationRunner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Renders a frame sequence from a running simulation by ray tracing, without the frame loop owning
 * the physics.
 *
 * <p>The physics is stepped only by a {@link SimulationRunner}: this driver hangs off the runner's
 * {@link SimulationRunner#runFrames(int, int, java.util.function.IntConsumer) frame driver}, so the
 * runner remains the sole owner of the step loop. At each frame boundary a caller-supplied hook
 * rebuilds the renderable {@link Scene} from the current simulation state (for example a mesh
 * reconstructed from a height field), the scene is ray traced, and the frame is written to the
 * output directory as a zero-padded PNG that shares
 * {@link RayTracedScene#renderToFile(File) the render-and-write stage} with
 * {@link RayTracedAnimation}. After the last frame an {@code encode.sh} script is written whose
 * {@code ffmpeg} frame list matches the frames on disk.</p>
 *
 * <p>The outer frame loop therefore does not drive the physics step — it only observes the state the
 * runner produces, as the simulation-loop contract requires.</p>
 *
 * @author Michael Murray
 * @see SimulationRunner#runFrames(int, int, java.util.function.IntConsumer)
 * @see RayTracedScene#renderToFile(File)
 */
public class RayTracedFrameSequence implements ConsoleFeatures {
	/** The default frames-per-second written into the encode script. */
	public static final int DEFAULT_FRAME_RATE = 30;

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

	/** The directory the frames and encode script are written to. */
	private final File outputDirectory;

	/** The zero-padding width of a frame index, so file names sort in frame order. */
	private final int indexWidth;

	/** The frames written so far, in order, for the encode script. */
	private final List<File> frameFiles;

	/** The frame rate written into the encode script. */
	private int frameRate;

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
		if (frameCount < 0) {
			throw new IllegalArgumentException("frameCount must not be negative (" + frameCount + ")");
		}

		this.runner = runner;
		this.stepsPerFrame = stepsPerFrame;
		this.frameCount = frameCount;
		this.sceneForFrame = sceneForFrame;
		this.params = params;
		this.outputDirectory = outputDirectory;
		this.indexWidth = Integer.toString(Math.max(0, frameCount - 1)).length();
		this.frameFiles = new ArrayList<>();
		this.frameRate = DEFAULT_FRAME_RATE;
	}

	/**
	 * Sets the frame rate written into the encode script.
	 *
	 * @param frameRate the frames per second; must be positive
	 * @throws IllegalArgumentException if {@code frameRate} is not positive
	 */
	public void setFrameRate(int frameRate) {
		if (frameRate <= 0) {
			throw new IllegalArgumentException("frameRate must be positive (" + frameRate + ")");
		}

		this.frameRate = frameRate;
	}

	/** Returns the frame rate written into the encode script. */
	public int getFrameRate() { return frameRate; }

	/**
	 * Returns the frames written so far, in order.
	 *
	 * @return the frame files
	 */
	public List<File> getFrameFiles() { return frameFiles; }

	/**
	 * Drives the simulation into the frame sequence: the runner steps the physics and this driver
	 * renders a frame at each frame boundary, then writes the encode script. The runner should not
	 * have been advanced before this call, since frame indices are derived from its step counter.
	 *
	 * @throws IllegalStateException if the output directory does not exist and cannot be created
	 */
	public void run() {
		if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
			throw new IllegalStateException("Could not create output directory " + outputDirectory);
		}

		runner.runFrames(stepsPerFrame, frameCount, this::renderFrame);
		writeEncodeScript();
	}

	/**
	 * Rebuilds the scene from the current simulation state and writes one rendered frame. A frame
	 * that cannot be written is reported as a warning, and the sequence continues.
	 *
	 * @param frameIndex the index of the frame to render
	 */
	protected void renderFrame(int frameIndex) {
		Scene<? extends ShadableSurface> scene = sceneForFrame.apply(frameIndex);
		File file = new File(outputDirectory, frameFileName(frameIndex));

		try {
			renderScene(scene, file);
			frameFiles.add(file);
			log("Wrote frame " + frameIndex + "/" + frameCount + ": " + file);
		} catch (IOException e) {
			warn("Could not write frame " + frameIndex + " to " + file, e);
		}
	}

	/**
	 * Ray traces the given scene with this driver's render parameters and writes the frame to the
	 * file as a PNG, through {@link RayTracedScene#renderToFile(File)}. This is the
	 * render step of a frame; it is the template-method hook a subclass overrides to render with a
	 * different engine or to post-process the frame.
	 *
	 * @param scene the scene reconstructed from the simulation state
	 * @param file  the file to write the frame to
	 * @throws IOException if the frame cannot be written
	 */
	protected void renderScene(Scene<? extends ShadableSurface> scene, File file) throws IOException {
		new RayTracedScene(scene, new FogParameters(), params).renderToFile(file);
	}

	/**
	 * Returns the file name of a frame, {@code frame_<zero-padded index>.png}.
	 *
	 * @param frameIndex the frame index
	 * @return the frame file name
	 */
	protected String frameFileName(int frameIndex) {
		return "frame_" + String.format("%0" + indexWidth + "d", frameIndex) + ".png";
	}

	/**
	 * Writes {@code encode.sh} to the output directory: an {@code ffmpeg} command that encodes the
	 * frame sequence as an MP4. The input pattern {@code frame_%0<width>d.png} with a start number
	 * of {@code 0} and {@code -frames:v <count>} references exactly the zero-padded frames this
	 * driver wrote, so the frame list matches the files on disk. The {@code ffmpeg} pattern consumes
	 * frames contiguously from {@code 0}, so a frame that failed to write bounds the sequence: the
	 * script covers only the {@link #writtenFrameCount() leading run of frames that were written},
	 * and no script is written when that run is empty.
	 */
	protected void writeEncodeScript() {
		int available = writtenFrameCount();

		if (available < frameCount) {
			warn("Only " + available + " of " + frameCount + " frames were written; the encode " +
					"script will cover the " + available + " contiguous frame(s) present");
		}

		if (available <= 0) {
			warn("No contiguous frames were written; skipping encode script");
			return;
		}

		File script = new File(outputDirectory, "encode.sh");
		String pattern = "frame_%0" + indexWidth + "d.png";

		try (PrintWriter out = new PrintWriter(new FileWriter(script))) {
			out.println("#!/bin/sh");
			out.println("ffmpeg -y -framerate " + frameRate + " -start_number 0 -i " + pattern +
					" -frames:v " + available + " -c:v libx264 -pix_fmt yuv420p output.mp4");
		} catch (IOException e) {
			warn("Could not write encode script " + script, e);
		}
	}

	/**
	 * Returns the number of leading frames, starting at frame {@code 0}, that were written
	 * contiguously. Because the encode script's {@code ffmpeg} pattern consumes frames contiguously
	 * from {@code 0}, a gap left by a failed frame write bounds the sequence the script can
	 * reference; this is that bound.
	 *
	 * @return the number of contiguous frames written from frame {@code 0}
	 */
	protected int writtenFrameCount() {
		Set<String> written = new HashSet<>();
		for (File frame : frameFiles) {
			written.add(frame.getName());
		}

		int count = 0;
		while (count < frameCount && written.contains(frameFileName(count))) {
			count++;
		}

		return count;
	}
}
