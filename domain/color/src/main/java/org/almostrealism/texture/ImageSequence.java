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

package org.almostrealism.texture;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.color.RGB;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A sequence of rendered image frames written to a directory on disk, together with the
 * {@code ffmpeg} script that encodes them as a video. This is the single owner of frame-sequence
 * output: it creates the output directory, names each frame with a zero-padded index (behind an
 * optional prefix) so the files sort in frame order, encodes a frame's pixels to its file through
 * {@link ImageCanvas}, tracks which frames were written, and emits an encode script bounded to the
 * frames actually present.
 *
 * <p>It is not specific to any renderer: a caller renders a frame to an {@link RGB} array (or an
 * {@link Evaluable} image producer) however it likes and hands it to {@link #writeFrame(int, RGB[][])}.
 * A frame that cannot be written is reported as a warning and the sequence continues, leaving a gap.
 * Because the encode script's {@code ffmpeg} pattern consumes frames contiguously from {@code 0}, a
 * gap bounds the sequence: the script covers only the {@link #writtenFrameCount() leading run of
 * frames written from frame 0}, and no script is written when that run is empty (any stale script
 * from a prior run of a reused output directory is removed in that case).</p>
 *
 * @author Michael Murray
 * @see ImageCanvas#encodeImageFile(RGB[][], File, int)
 */
public class ImageSequence implements ConsoleFeatures {
	/** The default frames-per-second written into the encode script. */
	public static final int DEFAULT_FRAME_RATE = 30;

	/** The default frame file-name prefix. */
	public static final String DEFAULT_NAME_PREFIX = "frame_";

	/** The directory the frames and encode script are written to. */
	private final File outputDirectory;

	/** The number of frames the sequence will hold, bounding the index width and the encode script. */
	private final int frameCount;

	/** The prefix placed before the zero-padded index in each frame file name. */
	private final String namePrefix;

	/** The zero-padding width of a frame index, so file names sort in frame order. */
	private final int indexWidth;

	/** The frames written so far, in order. */
	private final List<File> frameFiles;

	/** The frame rate written into the encode script. */
	private int frameRate;

	/**
	 * Creates a sequence of {@code frameCount} frames in the given directory, with the default
	 * {@value #DEFAULT_NAME_PREFIX} name prefix.
	 *
	 * @param outputDirectory the directory the frames and encode script are written to
	 * @param frameCount      the number of frames the sequence will hold; must not be negative
	 * @throws IllegalArgumentException if {@code frameCount} is negative
	 */
	public ImageSequence(File outputDirectory, int frameCount) {
		this(outputDirectory, frameCount, DEFAULT_NAME_PREFIX);
	}

	/**
	 * Creates a sequence of {@code frameCount} frames in the given directory, with the given name
	 * prefix placed before each frame's zero-padded index.
	 *
	 * @param outputDirectory the directory the frames and encode script are written to
	 * @param frameCount      the number of frames the sequence will hold; must not be negative
	 * @param namePrefix      the prefix placed before the zero-padded index in each frame file name;
	 *                        must be a safe filename fragment (letters, digits, {@code .}, {@code _}
	 *                        and {@code -}), so it cannot escape the output directory or inject shell
	 *                        syntax into the encode script
	 * @throws IllegalArgumentException if {@code frameCount} is negative, or {@code namePrefix} is
	 *                                  {@code null} or not a safe filename fragment
	 */
	public ImageSequence(File outputDirectory, int frameCount, String namePrefix) {
		if (frameCount < 0) {
			throw new IllegalArgumentException("frameCount must not be negative (" + frameCount + ")");
		}

		if (namePrefix == null || !namePrefix.matches("[A-Za-z0-9._-]*")) {
			throw new IllegalArgumentException("namePrefix must be a safe filename fragment " +
					"(letters, digits, '.', '_', '-'), but was " + namePrefix);
		}

		this.outputDirectory = outputDirectory;
		this.frameCount = frameCount;
		this.namePrefix = namePrefix;
		this.indexWidth = Integer.toString(Math.max(0, frameCount - 1)).length();
		this.frameFiles = new ArrayList<>();
		this.frameRate = DEFAULT_FRAME_RATE;
	}

	/** Returns the directory the frames and encode script are written to. */
	public File getOutputDirectory() { return outputDirectory; }

	/** Returns the number of frames the sequence will hold. */
	public int getFrameCount() { return frameCount; }

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
	 * Creates the output directory if it does not already exist, so a caller may write into a fresh
	 * directory without creating it first. Call this once before writing frames.
	 *
	 * @throws IllegalStateException if the output directory does not exist and cannot be created
	 */
	public void prepare() {
		if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
			throw new IllegalStateException("Could not create output directory " + outputDirectory);
		}
	}

	/**
	 * Returns the file name of a frame, {@code <prefix><zero-padded index>.png}.
	 *
	 * @param frameIndex the frame index
	 * @return the frame file name
	 */
	public String frameName(int frameIndex) {
		return namePrefix + String.format("%0" + indexWidth + "d", frameIndex) + ".png";
	}

	/**
	 * Returns the file a frame is written to, in the output directory.
	 *
	 * @param frameIndex the frame index
	 * @return the frame file
	 */
	public File frameFile(int frameIndex) {
		return new File(outputDirectory, frameName(frameIndex));
	}

	/**
	 * Encodes a rendered frame's pixels to its file as a PNG through {@link ImageCanvas} and tracks
	 * it. A frame that cannot be written is reported as a warning and the sequence continues.
	 *
	 * @param frameIndex the index of the frame
	 * @param image      the rendered pixels, indexed by column then row
	 */
	public void writeFrame(int frameIndex, RGB[][] image) {
		writeFrame(frameIndex, new Provider<>(image));
	}

	/**
	 * Evaluates a rendered frame's image producer and encodes the pixels to the frame's file as a
	 * PNG through {@link ImageCanvas}, then tracks it. A frame that cannot be written is reported as
	 * a warning and the sequence continues.
	 *
	 * @param frameIndex the index of the frame
	 * @param image      the image producer for the frame
	 */
	public void writeFrame(int frameIndex, Evaluable<RGB[][]> image) {
		File file = frameFile(frameIndex);

		try {
			ImageCanvas.encodeImageFile(image, file, ImageCanvas.PNGEncoding);
			frameFiles.add(file);
			log("Wrote frame " + frameIndex + "/" + frameCount + ": " + file);
		} catch (IOException e) {
			warn("Could not write frame " + frameIndex + " to " + file, e);
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
	public int writtenFrameCount() {
		Set<String> written = new HashSet<>();
		for (File frame : frameFiles) {
			written.add(frame.getName());
		}

		int count = 0;
		while (count < frameCount && written.contains(frameName(count))) {
			count++;
		}

		return count;
	}

	/**
	 * Writes {@code encode.sh} to the output directory: an {@code ffmpeg} command that encodes the
	 * frame sequence as an MP4. The input pattern {@code <prefix>%0<width>d.png} with a start number
	 * of {@code 0} and {@code -frames:v <count>} references exactly the zero-padded frames written, so
	 * the frame list matches the files on disk. The {@code ffmpeg} pattern consumes frames
	 * contiguously from {@code 0}, so a frame that failed to write bounds the sequence: the script
	 * covers only the {@link #writtenFrameCount() leading run of frames that were written}, and no
	 * script is written when that run is empty. When the run is empty any {@code encode.sh} already
	 * present from a prior run of a reused output directory is removed, so a stale script never
	 * survives a run that wrote nothing.
	 */
	public void writeEncodeScript() {
		int available = writtenFrameCount();
		File script = new File(outputDirectory, "encode.sh");

		if (available < frameCount) {
			warn("Only " + available + " of " + frameCount + " frames were written; the encode " +
					"script will cover the " + available + " contiguous frame(s) present");
		}

		if (available <= 0) {
			if (script.isFile() && !script.delete()) {
				warn("Could not remove stale encode script " + script);
			}

			warn("No contiguous frames were written; wrote no encode script");
			return;
		}

		String pattern = namePrefix + "%0" + indexWidth + "d.png";

		try (PrintWriter out = new PrintWriter(new FileWriter(script))) {
			out.println("#!/bin/sh");
			out.println("ffmpeg -y -framerate " + frameRate + " -start_number 0 -i " + pattern +
					" -frames:v " + available + " -c:v libx264 -pix_fmt yuv420p output.mp4");
		} catch (IOException e) {
			warn("Could not write encode script " + script, e);
		}
	}
}
