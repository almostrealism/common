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

package org.almostrealism.audio;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Behavioral tests for {@link WavFile}, the low-level PCM WAV reader/writer.
 *
 * <p>{@link WavFile} is pure host-side file I/O — it does not build a computation
 * graph — so these tests exercise it directly: header metadata accessors, the
 * signed 16-bit and unsigned 8-bit normalization paths, the several
 * array/buffer read and write shapes, RIFF word-alignment padding, partial
 * reads/writes, and the header/parameter validation error paths.</p>
 */
public class WavFileTest extends TestSuiteBase {

	/** Sample rate used for most fixtures. */
	private static final long SAMPLE_RATE = 44100L;

	/**
	 * Tolerance for 16-bit round-trip normalization. The write path scales by
	 * 32767 while the read path scales by 32768, so a written normalized value
	 * returns within roughly one part in 32768.
	 */
	private static final double TOL_16 = 1.0 / 16000.0;

	/**
	 * Creates a temporary file that is deleted on JVM exit.
	 *
	 * @return a fresh temp file path with a {@code .wav} suffix
	 * @throws IOException if the temp file cannot be created
	 */
	private File tempWav() throws IOException {
		File f = File.createTempFile("ar-wavfile-test", ".wav");
		f.deleteOnExit();
		return f;
	}

	/**
	 * Writes a two-channel signed 16-bit file and reads it back, asserting the
	 * normalized samples survive the round trip and the header metadata matches
	 * what was written.
	 */
	@Test(timeout = 60000)
	public void roundTrip16BitStereo() throws IOException {
		File file = tempWav();
		double[][] out = {
				{0.0, 0.5, -0.5, 0.999, -0.999},
				{0.25, -0.25, 0.75, -0.75, 0.1}
		};

		try (WavFile wav = WavFile.newWavFile(file, 2, out[0].length, 16, SAMPLE_RATE)) {
			int written = wav.writeFrames(out);
			Assert.assertEquals(5, written);
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			Assert.assertEquals(2, wav.getNumChannels());
			Assert.assertEquals(5, wav.getNumFrames());
			Assert.assertEquals(SAMPLE_RATE, wav.getSampleRate());
			Assert.assertEquals(16, wav.getValidBits());
			Assert.assertEquals(5.0 / SAMPLE_RATE, wav.getDuration(), 1e-12);

			double[][] in = new double[2][5];
			int read = wav.readFrames(in, 5);
			Assert.assertEquals(5, read);

			for (int c = 0; c < 2; c++) {
				for (int f = 0; f < 5; f++) {
					Assert.assertEquals("channel " + c + " frame " + f,
							out[c][f], in[c][f], TOL_16);
				}
			}
		}
	}

	/**
	 * Verifies the unsigned 8-bit path: the write offset/scale and the read
	 * offset/scale invert one another at the range endpoints, so -1.0, 0 and
	 * +1.0 survive exactly.
	 */
	@Test(timeout = 60000)
	public void roundTrip8BitEndpoints() throws IOException {
		File file = tempWav();
		double[][] out = {{-1.0, 1.0, -1.0, 1.0}};

		try (WavFile wav = WavFile.newWavFile(file, 1, out[0].length, 8, 22050L)) {
			wav.writeFrames(out);
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			Assert.assertEquals(1, wav.getNumChannels());
			Assert.assertEquals(8, wav.getValidBits());
			Assert.assertEquals(22050L, wav.getSampleRate());

			double[][] in = new double[1][4];
			wav.readFrames(in, 4);
			Assert.assertArrayEquals(out[0], in[0], 1e-9);
		}
	}

	/**
	 * Exercises the integer sample path (no normalization): raw sample values
	 * written via the channel-indexed int overload are returned bit-for-bit.
	 */
	@Test(timeout = 60000)
	public void roundTripIntSamples() throws IOException {
		File file = tempWav();
		int[][] out = {{0, 1000, -1000, 32000, -32000}};

		try (WavFile wav = WavFile.newWavFile(file, 1, out[0].length, 16, SAMPLE_RATE)) {
			wav.writeFrames(out, out[0].length);
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			int[][] in = new int[1][5];
			int read = wav.readFrames(in, 5);
			Assert.assertEquals(5, read);
			Assert.assertArrayEquals(out[0], in[0]);
		}
	}

	/**
	 * Exercises the long sample path via the flat (interleaved) overloads for a
	 * two-channel file.
	 */
	@Test(timeout = 60000)
	public void roundTripLongInterleaved() throws IOException {
		File file = tempWav();
		long[] out = {5L, -5L, 100L, -100L, 20000L, -20000L};

		try (WavFile wav = WavFile.newWavFile(file, 2, 3, 16, SAMPLE_RATE)) {
			int written = wav.writeFrames(out, 3);
			Assert.assertEquals(3, written);
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			long[] in = new long[6];
			int read = wav.readFrames(in, 3);
			Assert.assertEquals(3, read);
			Assert.assertArrayEquals(out, in);
		}
	}

	/**
	 * Reads normalized samples into channel-major {@link DoubleBuffer} and
	 * {@link FloatBuffer} staging views and checks the de-interleaving layout,
	 * where channel {@code c}, frame {@code f} lands at {@code c * stride + f}.
	 */
	@Test(timeout = 60000)
	public void readIntoNioBuffers() throws IOException {
		File file = tempWav();
		double[][] out = {
				{0.5, -0.5, 0.25},
				{-0.5, 0.5, -0.25}
		};

		try (WavFile wav = WavFile.newWavFile(file, 2, 3, 16, SAMPLE_RATE)) {
			wav.writeFrames(out);
		}

		int stride = 3;
		try (WavFile wav = WavFile.openWavFile(file)) {
			DoubleBuffer db = DoubleBuffer.allocate(2 * stride);
			int read = wav.readFrames(db, stride, 3);
			Assert.assertEquals(3, read);
			for (int c = 0; c < 2; c++) {
				for (int f = 0; f < 3; f++) {
					Assert.assertEquals(out[c][f], db.get(c * stride + f), TOL_16);
				}
			}
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			FloatBuffer fb = FloatBuffer.allocate(2 * stride);
			int read = wav.readFrames(fb, stride, 3);
			Assert.assertEquals(3, read);
			for (int c = 0; c < 2; c++) {
				for (int f = 0; f < 3; f++) {
					Assert.assertEquals(out[c][f], fb.get(c * stride + f), (float) TOL_16);
				}
			}
		}
	}

	/**
	 * Verifies that requesting more frames than remain returns only the frames
	 * available and that {@link WavFile#getFramesRemaining()} tracks progress.
	 */
	@Test(timeout = 60000)
	public void partialReadStopsAtEnd() throws IOException {
		File file = tempWav();
		double[][] out = {{0.1, 0.2, 0.3}};

		try (WavFile wav = WavFile.newWavFile(file, 1, 3, 16, SAMPLE_RATE)) {
			wav.writeFrames(out);
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			Assert.assertEquals(3, wav.getFramesRemaining());

			double[][] first = new double[1][2];
			Assert.assertEquals(2, wav.readFrames(first, 2));
			Assert.assertEquals(1, wav.getFramesRemaining());

			double[][] rest = new double[1][5];
			Assert.assertEquals(1, wav.readFrames(rest, 5));
			Assert.assertEquals(0, wav.getFramesRemaining());
		}
	}

	/**
	 * A write that supplies more frames than the file was declared to hold stops
	 * once the declared frame count is reached, returning the count actually
	 * written, and the persisted samples match the truncated prefix.
	 */
	@Test(timeout = 60000)
	public void writeStopsAtDeclaredFrameCount() throws IOException {
		File file = tempWav();
		double[][] out = {{0.1, 0.2, 0.3, 0.4, 0.5}};

		try (WavFile wav = WavFile.newWavFile(file, 1, 3, 16, SAMPLE_RATE)) {
			int written = wav.writeFrames(out, 5);
			Assert.assertEquals(3, written);
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			Assert.assertEquals(3, wav.getNumFrames());
			double[][] in = new double[1][3];
			Assert.assertEquals(3, wav.readFrames(in, 3));
			Assert.assertEquals(0.1, in[0][0], TOL_16);
			Assert.assertEquals(0.2, in[0][1], TOL_16);
			Assert.assertEquals(0.3, in[0][2], TOL_16);
		}
	}

	/**
	 * An odd number of 8-bit mono frames produces an odd data-chunk size, which
	 * forces the RIFF word-alignment padding byte on close. The file must still
	 * reopen cleanly and report the original (odd) frame count.
	 */
	@Test(timeout = 60000)
	public void wordAlignmentPaddingRoundTrips() throws IOException {
		File file = tempWav();
		double[][] out = {{-1.0, 1.0, -1.0}};

		try (WavFile wav = WavFile.newWavFile(file, 1, 3, 8, SAMPLE_RATE)) {
			wav.writeFrames(out);
		}

		Assert.assertEquals(48L, file.length());

		try (WavFile wav = WavFile.openWavFile(file)) {
			Assert.assertEquals(3, wav.getNumFrames());
			double[][] in = new double[1][3];
			Assert.assertEquals(3, wav.readFrames(in, 3));
			Assert.assertArrayEquals(out[0], in[0], 1e-9);
		}
	}

	/**
	 * Writing to an output stream with no backing {@link File} produces a valid
	 * RIFF/WAVE byte stream of the expected total length beginning with the
	 * ASCII {@code RIFF} magic.
	 */
	@Test(timeout = 60000)
	public void writeToStreamProducesExpectedByteCount() throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		double[][] out = {{0.0, 0.5, -0.5, 0.25}};

		try (WavFile wav = WavFile.newWavFile(bos, 1, 4, 16, SAMPLE_RATE)) {
			wav.writeFrames(out);
		}

		byte[] bytes = bos.toByteArray();
		Assert.assertEquals(52, bytes.length);
		Assert.assertEquals('R', bytes[0]);
		Assert.assertEquals('I', bytes[1]);
		Assert.assertEquals('F', bytes[2]);
		Assert.assertEquals('F', bytes[3]);
	}

	/**
	 * After {@link WavFile#close()}, the instance is in the CLOSED state and any
	 * further read must be rejected.
	 */
	@Test(timeout = 60000)
	public void readAfterCloseIsRejected() throws IOException {
		File file = tempWav();
		try (WavFile wav = WavFile.newWavFile(file, 1, 2, 16, SAMPLE_RATE)) {
			wav.writeFrames(new double[][]{{0.1, 0.2}});
		}

		WavFile wav = WavFile.openWavFile(file);
		wav.close();
		try {
			wav.readFrames(new double[1][1], 1);
			Assert.fail("Expected IOException reading from a closed WavFile");
		} catch (IOException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	/**
	 * A WavFile opened for reading rejects write calls, and one opened for
	 * writing rejects read calls.
	 */
	@Test(timeout = 60000)
	public void stateMismatchIsRejected() throws IOException {
		File file = tempWav();

		WavFile writer = WavFile.newWavFile(file, 1, 2, 16, SAMPLE_RATE);
		try {
			writer.readFrames(new double[1][1], 1);
			Assert.fail("Expected IOException reading from a writing WavFile");
		} catch (IOException expected) {
			Assert.assertNotNull(expected.getMessage());
		} finally {
			writer.writeFrames(new double[][]{{0.0, 0.0}});
			writer.close();
		}

		try (WavFile reader = WavFile.openWavFile(file)) {
			try {
				reader.writeFrames(new double[][]{{0.0}});
				Assert.fail("Expected IOException writing to a reading WavFile");
			} catch (IOException expected) {
				Assert.assertNotNull(expected.getMessage());
			}
		}
	}

	/**
	 * {@link WavFile#newWavFile} validates its parameters and rejects an illegal
	 * channel count.
	 */
	@Test(timeout = 60000)
	public void newWavFileRejectsZeroChannels() throws IOException {
		try {
			WavFile.newWavFile(new ByteArrayOutputStream(), 0, 4, 16, SAMPLE_RATE);
			Assert.fail("Expected IOException for zero channels");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("channels"));
		}
	}

	/**
	 * {@link WavFile#newWavFile} rejects a bit depth below the supported minimum.
	 */
	@Test(timeout = 60000)
	public void newWavFileRejectsTooFewValidBits() throws IOException {
		try {
			WavFile.newWavFile(new ByteArrayOutputStream(), 1, 4, 1, SAMPLE_RATE);
			Assert.fail("Expected IOException for validBits < 2");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("valid bits"));
		}
	}

	/**
	 * {@link WavFile#newWavFile} rejects a negative frame count.
	 */
	@Test(timeout = 60000)
	public void newWavFileRejectsNegativeFrames() throws IOException {
		try {
			WavFile.newWavFile(new ByteArrayOutputStream(), 1, -1, 16, SAMPLE_RATE);
			Assert.fail("Expected IOException for negative frame count");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("frames"));
		}
	}

	/**
	 * Opening a file whose first twelve bytes are not a RIFF/WAVE header fails
	 * with an error rather than returning a malformed WavFile.
	 */
	@Test(timeout = 60000)
	public void openRejectsNonWavContent() throws IOException {
		File file = tempWav();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(new byte[]{'N', 'O', 'T', 'A', 'W', 'A', 'V', 'F', 'I', 'L', 'E', '!', 0, 0});
		}

		try {
			WavFile.openWavFile(file);
			Assert.fail("Expected IOException opening non-WAV content");
		} catch (IOException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	/**
	 * Opening a file shorter than the twelve-byte header fails cleanly.
	 */
	@Test(timeout = 60000)
	public void openRejectsTruncatedHeader() throws IOException {
		File file = tempWav();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(new byte[]{'R', 'I', 'F', 'F'});
		}

		try {
			WavFile.openWavFile(file);
			Assert.fail("Expected IOException opening truncated header");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("header"));
		}
	}

	/**
	 * Exercises the flat (interleaved) {@code double[]} and {@code int[]} read
	 * and write overloads for a mono file.
	 */
	@Test(timeout = 60000)
	public void roundTripFlatDoubleAndInt() throws IOException {
		File dfile = tempWav();
		double[] dout = {0.0, 0.5, -0.5, 0.25};
		try (WavFile wav = WavFile.newWavFile(dfile, 1, 4, 16, SAMPLE_RATE)) {
			Assert.assertEquals(4, wav.writeFrames(dout, 4));
		}
		try (WavFile wav = WavFile.openWavFile(dfile)) {
			double[] din = new double[4];
			Assert.assertEquals(4, wav.readFrames(din, 4));
			Assert.assertArrayEquals(dout, din, TOL_16);
		}

		File ifile = tempWav();
		int[] iout = {0, 500, -500, 12345};
		try (WavFile wav = WavFile.newWavFile(ifile, 1, 4, 16, SAMPLE_RATE)) {
			Assert.assertEquals(4, wav.writeFrames(iout, 4));
		}
		try (WavFile wav = WavFile.openWavFile(ifile)) {
			int[] iin = new int[4];
			Assert.assertEquals(4, wav.readFrames(iin, 4));
			Assert.assertArrayEquals(iout, iin);
		}
	}

	/**
	 * Exercises the channel-indexed {@code long[][]} read and write overloads.
	 */
	@Test(timeout = 60000)
	public void roundTripLongChannelIndexed() throws IOException {
		File file = tempWav();
		long[][] out = {
				{1L, -2L, 3L},
				{-10L, 20L, -30L}
		};

		try (WavFile wav = WavFile.newWavFile(file, 2, 3, 16, SAMPLE_RATE)) {
			Assert.assertEquals(3, wav.writeFrames(out, 3));
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			long[][] in = new long[2][3];
			Assert.assertEquals(3, wav.readFrames(in, 3));
			Assert.assertArrayEquals(out[0], in[0]);
			Assert.assertArrayEquals(out[1], in[1]);
		}
	}

	/**
	 * The zero-argument {@link WavFile#writeFrames(double[][])} convenience
	 * writes every frame supplied, inferred from the buffer length.
	 */
	@Test(timeout = 60000)
	public void writeFramesConvenienceWritesAllFrames() throws IOException {
		File file = tempWav();
		double[][] out = {{0.1, -0.1, 0.2, -0.2, 0.3}};

		try (WavFile wav = WavFile.newWavFile(file, 1, 5, 16, SAMPLE_RATE)) {
			Assert.assertEquals(5, wav.writeFrames(out));
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			Assert.assertEquals(5, wav.getNumFrames());
			double[][] in = new double[1][5];
			Assert.assertEquals(5, wav.readFrames(in, 5));
			Assert.assertArrayEquals(out[0], in[0], TOL_16);
		}
	}

	/**
	 * {@link WavFile#display(PrintStream)} reports the parsed header fields.
	 */
	@Test(timeout = 60000)
	public void displayReportsHeaderFields() throws IOException {
		File file = tempWav();
		try (WavFile wav = WavFile.newWavFile(file, 2, 7, 16, SAMPLE_RATE)) {
			wav.writeFrames(new double[][]{new double[7], new double[7]});
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			wav.display(new PrintStream(bos, true, StandardCharsets.UTF_8));
			String text = bos.toString(StandardCharsets.UTF_8);
			Assert.assertTrue(text.contains("Channels: 2"));
			Assert.assertTrue(text.contains("Frames: 7"));
			Assert.assertTrue(text.contains("Sample Rate: " + SAMPLE_RATE));
			Assert.assertTrue(text.contains("Valid Bits: 16"));
		}
	}

	/**
	 * Writing and then reading more than one internal buffer's worth of audio
	 * exercises the buffer-flush path on write and the buffer-refill path on
	 * read; the first and last samples must still survive the round trip.
	 */
	@Test(timeout = 60000)
	public void largeBufferRoundTrips() throws IOException {
		File file = tempWav();
		int frames = 4000;
		double[][] out = {new double[frames]};
		for (int i = 0; i < frames; i++) {
			out[0][i] = ((i % 2 == 0) ? 1 : -1) * (0.5 + 0.25 * (i % 3));
		}

		try (WavFile wav = WavFile.newWavFile(file, 1, frames, 16, SAMPLE_RATE)) {
			Assert.assertEquals(frames, wav.writeFrames(out));
		}

		try (WavFile wav = WavFile.openWavFile(file)) {
			double[][] in = new double[1][frames];
			Assert.assertEquals(frames, wav.readFrames(in, frames));
			Assert.assertEquals(out[0][0], in[0][0], TOL_16);
			Assert.assertEquals(out[0][frames - 1], in[0][frames - 1], TOL_16);
			Assert.assertEquals(out[0][2047], in[0][2047], TOL_16);
			Assert.assertEquals(out[0][2048], in[0][2048], TOL_16);
		}
	}

	/**
	 * A RIFF container whose type id is not {@code WAVE} is rejected.
	 */
	@Test(timeout = 60000)
	public void openRejectsWrongRiffType() throws IOException {
		File file = tempWav();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'X', 'A', 'V', 'E'});
		}

		try {
			WavFile.openWavFile(file);
			Assert.fail("Expected IOException for wrong RIFF type id");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("riff type"));
		}
	}

	/**
	 * A header whose declared chunk size does not match the file length is
	 * rejected.
	 */
	@Test(timeout = 60000)
	public void openRejectsChunkSizeMismatch() throws IOException {
		File file = tempWav();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(new byte[]{'R', 'I', 'F', 'F', 99, 0, 0, 0, 'W', 'A', 'V', 'E'});
		}

		try {
			WavFile.openWavFile(file);
			Assert.fail("Expected IOException for chunk size mismatch");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("size"));
		}
	}

	/**
	 * A data chunk that appears before the format chunk is rejected, because the
	 * format information is required to interpret the data.
	 */
	@Test(timeout = 60000)
	public void openRejectsDataBeforeFormat() throws IOException {
		File file = tempWav();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(new byte[]{
					'R', 'I', 'F', 'F', 12, 0, 0, 0, 'W', 'A', 'V', 'E',
					'd', 'a', 't', 'a', 0, 0, 0, 0
			});
		}

		try {
			WavFile.openWavFile(file);
			Assert.fail("Expected IOException for data chunk before format chunk");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().toLowerCase().contains("format"));
		}
	}
}
