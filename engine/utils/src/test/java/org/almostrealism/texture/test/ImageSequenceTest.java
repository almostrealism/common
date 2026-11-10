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

import org.almostrealism.color.RGB;
import org.almostrealism.texture.ImageSequence;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Tests for {@link ImageSequence}, the single owner of frame-sequence output: zero-padded (and
 * optionally prefixed) frame naming, encoding a frame's pixels through {@code ImageCanvas}, tracking
 * which frames were written, an {@code ffmpeg} encode script bounded to the contiguous run of frames
 * present, output-directory creation, and warn-and-continue robustness when a frame cannot be written.
 */
public class ImageSequenceTest extends TextureTestBase {
	/**
	 * Builds a trivial 2x2 red image.
	 *
	 * @return the image pixels
	 */
	private RGB[][] redImage() {
		return solidColorImage(2, 2, new RGB(1.0, 0.0, 0.0));
	}

	/**
	 * Frame names are the index zero-padded to the width of the largest index, behind the prefix, so
	 * they sort in frame order; the default prefix is {@code frame_}.
	 */
	@Test(timeout = 30000)
	public void namesFramesZeroPadded() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-names").toFile();

		ImageSequence defaults = new ImageSequence(dir, 100);
		Assert.assertEquals("frame_05.png", defaults.frameName(5));
		Assert.assertEquals("frame_42.png", defaults.frameName(42));
		Assert.assertEquals(new File(dir, "frame_05.png"), defaults.frameFile(5));

		ImageSequence prefixed = new ImageSequence(dir, 100, "shot_");
		Assert.assertEquals("shot_05.png", prefixed.frameName(5));

		// A single-frame sequence still names frame 0 with at least one digit
		Assert.assertEquals("frame_0.png", new ImageSequence(dir, 1).frameName(0));
	}

	/**
	 * Each written frame is encoded to its file as a decodable PNG and tracked in order.
	 */
	@Test(timeout = 30000)
	public void writesAndTracksFrames() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-write").toFile();
		ImageSequence sequence = new ImageSequence(dir, 3);
		sequence.prepare();

		for (int i = 0; i < 3; i++) {
			sequence.writeFrame(i, redImage());
		}

		Assert.assertEquals(3, sequence.getFrameFiles().size());
		for (int i = 0; i < 3; i++) {
			File frame = new File(dir, "frame_" + i + ".png");
			Assert.assertEquals(frame, sequence.getFrameFiles().get(i));
			Assert.assertTrue("frame " + i + " was not written", frame.isFile());

			BufferedImage decoded = ImageIO.read(frame);
			Assert.assertNotNull("frame " + i + " is not a decodable PNG", decoded);
			Assert.assertEquals(2, decoded.getWidth());
			Assert.assertEquals(2, decoded.getHeight());
		}
	}

	/**
	 * The encode script covers the whole sequence when every frame is written: its {@code ffmpeg}
	 * input pattern matches the zero-padded file names and its frame count matches the frames present.
	 */
	@Test(timeout = 30000)
	public void contiguousRunEncodeScript() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-encode").toFile();
		ImageSequence sequence = new ImageSequence(dir, 12);
		sequence.prepare();
		sequence.setFrameRate(24);

		for (int i = 0; i < 12; i++) {
			sequence.writeFrame(i, redImage());
		}

		Assert.assertEquals(12, sequence.writtenFrameCount());
		sequence.writeEncodeScript();

		File encode = new File(dir, "encode.sh");
		Assert.assertTrue("encode script was not written", encode.isFile());
		String script = new String(Files.readAllBytes(encode.toPath()), StandardCharsets.UTF_8);
		Assert.assertTrue("encode script does not reference the frame pattern",
				script.contains("frame_%02d.png"));
		Assert.assertTrue("encode script frame count does not match", script.contains("-frames:v 12"));
		Assert.assertTrue("encode script does not honour the frame rate", script.contains("-framerate 24"));
	}

	/**
	 * A gap left by a frame that is never written bounds the encode script to the leading contiguous
	 * run from frame {@code 0}, so the script never references a missing frame.
	 */
	@Test(timeout = 30000)
	public void gapBoundsEncodeScript() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-gap").toFile();
		ImageSequence sequence = new ImageSequence(dir, 4);
		sequence.prepare();

		sequence.writeFrame(0, redImage());
		// frame 1 is skipped entirely
		sequence.writeFrame(2, redImage());
		sequence.writeFrame(3, redImage());

		Assert.assertEquals(1, sequence.writtenFrameCount());
		sequence.writeEncodeScript();

		File encode = new File(dir, "encode.sh");
		Assert.assertTrue("encode script was not written", encode.isFile());
		String script = new String(Files.readAllBytes(encode.toPath()), StandardCharsets.UTF_8);
		Assert.assertTrue("encode script should cover only the contiguous frame",
				script.contains("-frames:v 1"));
		Assert.assertFalse("encode script must not reference beyond the gap",
				script.contains("-frames:v 4"));
	}

	/**
	 * When no frame is written, no encode script is produced rather than one referencing frames that
	 * do not exist.
	 */
	@Test(timeout = 30000)
	public void noFramesWritesNoEncodeScript() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-none").toFile();
		ImageSequence sequence = new ImageSequence(dir, 3);
		sequence.prepare();

		Assert.assertEquals(0, sequence.writtenFrameCount());
		sequence.writeEncodeScript();

		Assert.assertFalse("no encode script should be written", new File(dir, "encode.sh").exists());
	}

	/**
	 * A frame whose encode fails is reported and skipped rather than aborting the sequence: it is not
	 * tracked, the remaining frames are still written, and the gap bounds the encode script.
	 */
	@Test(timeout = 30000)
	public void failedWriteIsSkipped() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-fail").toFile();
		ImageSequence sequence = new ImageSequence(dir, 3);
		sequence.prepare();

		// Occupy frame 1's path with a directory so its encode fails
		Assert.assertTrue("could not block frame 1", new File(dir, "frame_1.png").mkdir());

		sequence.writeFrame(0, redImage());
		sequence.writeFrame(1, redImage());
		sequence.writeFrame(2, redImage());

		Assert.assertEquals(2, sequence.getFrameFiles().size());
		Assert.assertFalse("blocked frame must not be tracked",
				sequence.getFrameFiles().contains(new File(dir, "frame_1.png")));
		Assert.assertEquals(1, sequence.writtenFrameCount());
	}

	/**
	 * {@link ImageSequence#prepare()} creates the output directory when it does not exist, and throws
	 * {@link IllegalStateException} when it cannot be created (its path is blocked by a regular file).
	 */
	@Test(timeout = 30000)
	public void createsOutputDirectoryOrThrows() throws IOException {
		File parent = Files.createTempDirectory("image-sequence-mkdir").toFile();

		File fresh = new File(parent, "frames");
		Assert.assertFalse("output directory should not exist yet", fresh.exists());
		new ImageSequence(fresh, 1).prepare();
		Assert.assertTrue("output directory was not created", fresh.isDirectory());

		File blocker = new File(parent, "blocker");
		Files.write(blocker.toPath(), new byte[] { 1 });
		try {
			new ImageSequence(new File(blocker, "frames"), 1).prepare();
			Assert.fail("expected an IllegalStateException for an uncreatable output directory");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	/**
	 * A negative frame count and a non-positive frame rate are rejected with
	 * {@link IllegalArgumentException}, while a positive frame rate is accepted and reported.
	 */
	@Test(timeout = 30000)
	public void rejectsInvalidArguments() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-invalid").toFile();

		assertThrowsIllegalArgument(() -> new ImageSequence(dir, -1));

		ImageSequence sequence = new ImageSequence(dir, 3);
		assertThrowsIllegalArgument(() -> sequence.setFrameRate(0));
		assertThrowsIllegalArgument(() -> sequence.setFrameRate(-30));

		sequence.setFrameRate(48);
		Assert.assertEquals(48, sequence.getFrameRate());
	}

	/**
	 * A name prefix that is not a safe filename fragment is rejected, so it cannot escape the output
	 * directory with a path separator or inject shell syntax into the encode script; a plain prefix
	 * and the empty prefix are accepted.
	 */
	@Test(timeout = 30000)
	public void rejectsUnsafeNamePrefix() throws IOException {
		File dir = Files.createTempDirectory("image-sequence-prefix").toFile();

		assertThrowsIllegalArgument(() -> new ImageSequence(dir, 3, "../escape_"));
		assertThrowsIllegalArgument(() -> new ImageSequence(dir, 3, "sub/dir_"));
		assertThrowsIllegalArgument(() -> new ImageSequence(dir, 3, "shot; rm -rf x;"));
		assertThrowsIllegalArgument(() -> new ImageSequence(dir, 3, "$(whoami)"));
		assertThrowsIllegalArgument(() -> new ImageSequence(dir, 3, (String) null));

		Assert.assertEquals("shot_00.png", new ImageSequence(dir, 100, "shot_").frameName(0));
		Assert.assertEquals("0.png", new ImageSequence(dir, 1, "").frameName(0));
	}

	/**
	 * Asserts that {@code action} throws {@link IllegalArgumentException}.
	 *
	 * @param action the action expected to throw
	 */
	private static void assertThrowsIllegalArgument(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected an IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
