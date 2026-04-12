/*
 * Inspired by the work of A.Greensted (http://www.labbookpages.co.uk)
 *
 * File format is based on the information from
 * http://www.sonicspot.com/guide/wavefiles.html
 * http://www.blitter.com/~russtopia/MIDI/~jglatt/tech/wave.htm
 */

package org.almostrealism.audio;

import org.almostrealism.collect.PackedCollection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Low-level WAV audio file reader and writer.
 *
 * <p>WavFile provides direct access to read and write uncompressed PCM WAV files,
 * supporting multiple bit depths (2-64 bits), sample rates, and channel counts.
 * It handles the RIFF/WAVE file format including header parsing, data chunk
 * management, and proper byte alignment.</p>
 *
 * <h2>Reading WAV Files</h2>
 * <pre>{@code
 * try (WavFile wav = WavFile.openWavFile(new File("input.wav"))) {
 *     int channels = wav.getNumChannels();
 *     long frames = wav.getNumFrames();
 *     long sampleRate = wav.getSampleRate();
 *
 *     double[][] buffer = new double[channels][(int) frames];
 *     wav.readFrames(buffer, (int) frames);
 * }
 * }</pre>
 *
 * <h2>Writing WAV Files</h2>
 * <pre>{@code
 * try (WavFile wav = WavFile.newWavFile(new File("output.wav"),
 *         2, 44100, 24, 44100L)) {
 *     double[][] buffer = new double[2][44100];
 *     wav.writeFrames(buffer, 44100);
 * }
 * }</pre>
 *
 * @see WaveData
 * @see WaveOutput
 */
public class WavFile implements AutoCloseable {

	/**
	 * The IO state of a WavFile instance, used for sanity checking operations.
	 */
	private enum ReaderState {
		/** The file is open for reading. */
		READING,
		/** The file is open for writing. */
		WRITING,
		/** The file has been closed. */
		CLOSED
	}

	/** Size of the internal IO buffer in bytes. */
	private final static int BUFFER_SIZE = 4096;

	/** Chunk ID for the format chunk in a WAV file. */
	private final static int FMT_CHUNK_ID = 0x20746D66;
	/** Chunk ID for the data chunk in a WAV file. */
	private final static int DATA_CHUNK_ID = 0x61746164;
	/** Chunk ID for the RIFF container chunk. */
	private final static int RIFF_CHUNK_ID = 0x46464952;
	/** Type ID identifying the WAVE format within the RIFF container. */
	private final static int RIFF_TYPE_ID = 0x45564157;

	/** The file that will be read from or written to. */
	private File file;
	/** The current IO state of the WAV file, used for sanity checking. */
	private ReaderState readerState;
	/** The number of bytes required to store a single sample. */
	private int bytesPerSample;
	/** The number of frames within the data section. */
	private long numFrames;
	/** The output stream used for writing data. */
	private OutputStream oStream;
	/** The input stream used for reading data. */
	private FileInputStream iStream;
	/** Scaling factor used for int-to-float conversion. */
	private double floatScale;
	/** Offset factor used for int-to-float conversion. */
	private double floatOffset;
	/** Whether an extra byte at the end of the data chunk is required for word alignment. */
	private boolean wordAlignAdjust;

	/** Number of audio channels (2 bytes unsigned, 1 to 65535). */
	private int numChannels;
	/** Sample rate in Hz (4 bytes unsigned, stored as long to handle unsigned values). */
	private long sampleRate;
	/** Block alignment in bytes (2 bytes unsigned). */
	private int blockAlign;
	/** Number of valid bits per sample (2 bytes unsigned). */
	private int validBits;

	/** Local buffer used for IO operations. */
	private final byte[] buffer;
	/** Points to the current position in the local buffer. */
	private int bufferPointer;
	/** Number of bytes read after the last read into the local buffer. */
	private int bytesRead;
	/** Current number of frames read or written. */
	private long frameCounter;

	/**
	 * Private constructor. Use {@link #newWavFile} or {@link #openWavFile} to create instances.
	 */
	private WavFile() {
		buffer = new byte[BUFFER_SIZE];
	}

	/**
	 * Returns the number of audio channels in this WAV file.
	 *
	 * @return the number of channels
	 */
	public int getNumChannels() {
		return numChannels;
	}

	/**
	 * Returns the total number of frames in this WAV file.
	 *
	 * @return the number of frames
	 */
	public long getNumFrames() {
		return numFrames;
	}

	/**
	 * Returns the duration of this WAV file in seconds.
	 *
	 * @return the duration in seconds
	 */
	public double getDuration() { return ((double) numFrames) / getSampleRate(); }

	/**
	 * Returns the number of frames remaining to be read.
	 *
	 * @return the number of unread frames
	 */
	public long getFramesRemaining() { return numFrames - frameCounter; }

	/**
	 * Returns the sample rate of this WAV file in Hz.
	 *
	 * @return the sample rate in Hz
	 */
	public long getSampleRate() { return sampleRate; }

	/**
	 * Returns the number of valid bits per sample.
	 *
	 * @return the valid bit depth
	 */
	public int getValidBits() { return validBits; }

	/**
	 * Creates a new WavFile for writing to the specified file.
	 *
	 * @param file        the output file
	 * @param numChannels the number of audio channels
	 * @param numFrames   the total number of frames to write
	 * @param validBits   the bit depth
	 * @param sampleRate  the sample rate in Hz
	 * @return a new WavFile ready for writing
	 * @throws IOException if the file cannot be opened or the parameters are invalid
	 */
	public static WavFile newWavFile(File file, int numChannels, long numFrames, int validBits, long sampleRate) throws IOException {
		return newWavFile(file, new FileOutputStream(file), numChannels, numFrames, validBits, sampleRate);
	}

	/**
	 * Creates a new WavFile for writing to the specified output stream.
	 *
	 * @param stream      the output stream to write to
	 * @param numChannels the number of audio channels
	 * @param numFrames   the total number of frames to write
	 * @param validBits   the bit depth
	 * @param sampleRate  the sample rate in Hz
	 * @return a new WavFile ready for writing
	 * @throws IOException if the parameters are invalid
	 */
	public static WavFile newWavFile(OutputStream stream, int numChannels, long numFrames, int validBits, long sampleRate) throws IOException {
		return newWavFile(null, stream, numChannels, numFrames, validBits, sampleRate);
	}

	/**
	 * Creates a new WavFile for writing to a file and/or stream.
	 *
	 * @param file        the output file, or null if writing to stream only
	 * @param stream      the output stream to write to
	 * @param numChannels the number of audio channels
	 * @param numFrames   the total number of frames to write
	 * @param validBits   the bit depth
	 * @param sampleRate  the sample rate in Hz
	 * @return a new WavFile ready for writing
	 * @throws IOException if the parameters are invalid
	 */
	protected static WavFile newWavFile(File file, OutputStream stream, int numChannels, long numFrames, int validBits, long sampleRate) throws IOException {
		WavFile wavFile = new WavFile();
		wavFile.file = file;
		wavFile.numChannels = numChannels;
		wavFile.numFrames = numFrames;
		wavFile.sampleRate = sampleRate;
		wavFile.bytesPerSample = (validBits + 7) / 8;
		wavFile.blockAlign = wavFile.bytesPerSample * numChannels;
		wavFile.validBits = validBits;

		// Sanity check arguments
		if (numChannels < 1 || numChannels > 65535)
			throw new IOException("Illegal number of channels, valid range 1 to 65536");
		if (numFrames < 0) throw new IOException("Number of frames must be positive");
		if (validBits < 2 || validBits > 65535)
			throw new IOException("Illegal number of valid bits, valid range 2 to 65536");
		if (sampleRate < 0) throw new IOException("Sample rate must be positive");

		// Output stream for writing data
		wavFile.oStream = stream;

		// Calculate the chunk sizes
		long dataChunkSize = wavFile.blockAlign * numFrames;
		long mainChunkSize = 4 +    // Riff Type
				8 +    // Format ID and size
				16 +   // Format data
				8 +    // Data ID and size
				dataChunkSize;

		// Chunks must be word aligned, so if odd number of audio data bytes
		// adjust the main chunk size
		if (dataChunkSize % 2 == 1) {
			mainChunkSize += 1;
			wavFile.wordAlignAdjust = true;
		} else {
			wavFile.wordAlignAdjust = false;
		}

		// Set the main chunk size
		putLE(RIFF_CHUNK_ID, wavFile.buffer, 0, 4);
		putLE(mainChunkSize, wavFile.buffer, 4, 4);
		putLE(RIFF_TYPE_ID, wavFile.buffer, 8, 4);

		// Write out the header
		wavFile.oStream.write(wavFile.buffer, 0, 12);

		// Put format data in buffer
		long averageBytesPerSecond = sampleRate * wavFile.blockAlign;

		putLE(FMT_CHUNK_ID, wavFile.buffer, 0, 4);        // Chunk ID
		putLE(16, wavFile.buffer, 4, 4);        // Chunk Data Size
		putLE(1, wavFile.buffer, 8, 2);        // Compression Code (Uncompressed)
		putLE(numChannels, wavFile.buffer, 10, 2);        // Number of channels
		putLE(sampleRate, wavFile.buffer, 12, 4);        // Sample Rate
		putLE(averageBytesPerSecond, wavFile.buffer, 16, 4);        // Average Bytes Per Second
		putLE(wavFile.blockAlign, wavFile.buffer, 20, 2);        // Block Align
		putLE(validBits, wavFile.buffer, 22, 2);        // Valid Bits

		// Write Format Chunk
		wavFile.oStream.write(wavFile.buffer, 0, 24);

		// Start Data Chunk
		putLE(DATA_CHUNK_ID, wavFile.buffer, 0, 4);        // Chunk ID
		putLE(dataChunkSize, wavFile.buffer, 4, 4);        // Chunk Data Size

		// Write Format Chunk
		wavFile.oStream.write(wavFile.buffer, 0, 8);

		// Calculate the scaling factor for converting to a normalised double
		if (wavFile.validBits > 8) {
			// If more than 8 validBits, data is signed
			// Conversion required multiplying by magnitude of max positive value
			wavFile.floatOffset = 0;
			wavFile.floatScale = Long.MAX_VALUE >> (64 - wavFile.validBits);
		} else {
			// Else if 8 or less validBits, data is unsigned
			// Conversion required dividing by max positive value
			wavFile.floatOffset = 1;
			wavFile.floatScale = 0.5 * ((1 << wavFile.validBits) - 1);
		}

		// Finally, set the IO State
		wavFile.bufferPointer = 0;
		wavFile.bytesRead = 0;
		wavFile.frameCounter = 0;
		wavFile.readerState = ReaderState.WRITING;

		return wavFile;
	}

	/**
	 * Extracts a single channel from a double[][] audio buffer as a traversed PackedCollection.
	 *
	 * @param data the multi-channel audio data, indexed as [channel][frame]
	 * @param chan the channel index to extract
	 * @return a PackedCollection containing the channel data
	 */
	public static PackedCollection channel(double[][] data, int chan) {
		return channel(data, chan, 0);
	}

	/**
	 * Extracts a single channel from a double[][] audio buffer as a traversed PackedCollection,
	 * optionally padding with extra frames.
	 *
	 * @param data      the multi-channel audio data, indexed as [channel][frame]
	 * @param chan      the channel index to extract
	 * @param padFrames the number of zero-padded frames to append
	 * @return a PackedCollection containing the channel data with optional padding
	 */
	public static PackedCollection channel(double[][] data, int chan, int padFrames) {
		PackedCollection waveform = new PackedCollection(data[chan].length + padFrames);
		waveform.setMem(0, data[chan]);
		return waveform.traverse(1);
	}

	/**
	 * Extracts a single channel from an int[][] audio buffer as a PackedCollection.
	 *
	 * @param data the multi-channel audio data, indexed as [channel][frame]
	 * @param chan the channel index to extract
	 * @return a PackedCollection containing the channel data as doubles
	 */
	public static PackedCollection channel(int[][] data, int chan) {
		PackedCollection waveform = new PackedCollection(data[chan].length);

		int index = 0;
		for (double frame : data[chan]) waveform.setMem(index++, frame);
		return waveform;
	}

	/**
	 * Extracts a single channel from an int[][] audio buffer as a scalar-traversed PackedCollection.
	 *
	 * @param data the multi-channel audio data, indexed as [channel][frame]
	 * @param chan the channel index to extract
	 * @return a scalar-traversed PackedCollection containing the channel data
	 * @deprecated Use {@link #channel(int[][], int)} instead
	 */
	@Deprecated
	public static PackedCollection channelScalar(int[][] data, int chan) {
		PackedCollection waveform = new PackedCollection(data[chan].length).traverse(1);

		int index = 0;
		for (int frame : data[chan]) waveform.setMem(index++, frame);
		return waveform;
	}

	/** Opens and parses the header of the specified WAV file for reading. */
	public static WavFile openWavFile(File file) throws IOException {
		// Instantiate new Wavfile and store the file reference
		WavFile wavFile = new WavFile();
		wavFile.file = file;

		// Create a new file input stream for reading file data
		wavFile.iStream = new FileInputStream(file);

		// Read the first 12 bytes of the file
		int bytesRead = wavFile.iStream.read(wavFile.buffer, 0, 12);
		if (bytesRead != 12) throw new IOException("Not enough wav file bytes for header");

		// Extract parts from the header
		long riffChunkID = getLE(wavFile.buffer, 0, 4);
		long chunkSize = getLE(wavFile.buffer, 4, 4);
		long riffTypeID = getLE(wavFile.buffer, 8, 4);

		// Check the header bytes contains the correct signature
		if (riffChunkID != RIFF_CHUNK_ID) throw new IOException("Invalid Wav Header data, incorrect riff chunk ID");
		if (riffTypeID != RIFF_TYPE_ID) throw new IOException("Invalid Wav Header data, incorrect riff type ID");

		// Check that the file size matches the number of bytes listed in header
		if (file.length() != chunkSize + 8) {
			throw new IOException("Header chunk size (" + chunkSize + ") does not match file size (" + file.length() + ")");
		}

		boolean foundFormat = false;
		boolean foundData = false;

		// Search for the Format and Data Chunks
		while (true) {
			// Read the first 8 bytes of the chunk (ID and chunk size)
			bytesRead = wavFile.iStream.read(wavFile.buffer, 0, 8);
			if (bytesRead == -1) throw new IOException("Reached end of file without finding format chunk");
			if (bytesRead != 8) throw new IOException("Could not read chunk header");

			// Extract the chunk ID and Size
			long chunkID = getLE(wavFile.buffer, 0, 4);
			chunkSize = getLE(wavFile.buffer, 4, 4);

			// Word align the chunk size
			// chunkSize specifies the number of bytes holding data. However,
			// the data should be word aligned (2 bytes) so we need to calculate
			// the actual number of bytes in the chunk
			long numChunkBytes = (chunkSize % 2 == 1) ? chunkSize + 1 : chunkSize;

			if (chunkID == FMT_CHUNK_ID) {
				// Flag that the format chunk has been found
				foundFormat = true;

				// Read in the header info
				bytesRead = wavFile.iStream.read(wavFile.buffer, 0, 16);

				// Check this is uncompressed data
				int compressionCode = (int) getLE(wavFile.buffer, 0, 2);
				if (compressionCode != 1)
					throw new IOException("Compression Code " + compressionCode + " not supported");

				// Extract the format information
				wavFile.numChannels = (int) getLE(wavFile.buffer, 2, 2);
				wavFile.sampleRate = getLE(wavFile.buffer, 4, 4);
				wavFile.blockAlign = (int) getLE(wavFile.buffer, 12, 2);
				wavFile.validBits = (int) getLE(wavFile.buffer, 14, 2);

				if (wavFile.numChannels == 0)
					throw new IOException("Number of channels specified in header is equal to zero");
				if (wavFile.blockAlign == 0) throw new IOException("Block Align specified in header is equal to zero");
				if (wavFile.validBits < 2) throw new IOException("Valid Bits specified in header is less than 2");
				if (wavFile.validBits > 64)
					throw new IOException("Valid Bits specified in header is greater than 64, this is greater than a long can hold");

				// Calculate the number of bytes required to hold 1 sample
				wavFile.bytesPerSample = (wavFile.validBits + 7) / 8;
				if (wavFile.bytesPerSample * wavFile.numChannels != wavFile.blockAlign)
					throw new IOException("Block Align does not agree with bytes required for validBits and number of channels");

				// Account for number of format bytes and then skip over
				// any extra format bytes
				numChunkBytes -= 16;
				if (numChunkBytes > 0) wavFile.iStream.skip(numChunkBytes);
			} else if (chunkID == DATA_CHUNK_ID) {
				// Check if we've found the format chunk,
				// If not, throw an exception as we need the format information
				// before we can read the data chunk
				if (!foundFormat) throw new IOException("Data chunk found before Format chunk");

				// Check that the chunkSize (wav data length) is a multiple of the
				// block align (bytes per frame)
				if (chunkSize % wavFile.blockAlign != 0)
					throw new IOException("Data Chunk size is not multiple of Block Align");

				// Calculate the number of frames
				wavFile.numFrames = chunkSize / wavFile.blockAlign;

				// Flag that we've found the wave data chunk
				foundData = true;

				break;
			} else {
				// If an unknown chunk ID is found, just skip over the chunk data
				wavFile.iStream.skip(numChunkBytes);
			}
		}

		// Throw an exception if no data chunk has been found
		if (!foundData) throw new IOException("Did not find a data chunk");

		// Calculate the scaling factor for converting to a normalised double
		if (wavFile.validBits > 8) {
			// If more than 8 validBits, data is signed
			// Conversion required dividing by magnitude of max negative value
			wavFile.floatOffset = 0;
			wavFile.floatScale = 1 << (wavFile.validBits - 1);
		} else {
			// Else if 8 or less validBits, data is unsigned
			// Conversion required dividing by max positive value
			wavFile.floatOffset = -1;
			wavFile.floatScale = 0.5 * ((1 << wavFile.validBits) - 1);
		}

		wavFile.bufferPointer = 0;
		wavFile.bytesRead = 0;
		wavFile.frameCounter = 0;
		wavFile.readerState = ReaderState.READING;

		return wavFile;
	}

	/**
	 * Reads a little-endian value from a byte buffer.
	 *
	 * @param buffer   the byte array to read from
	 * @param pos      the starting position in the buffer
	 * @param numBytes the number of bytes to read
	 * @return the value read as a long
	 */
	private static long getLE(byte[] buffer, int pos, int numBytes) {
		numBytes--;
		pos += numBytes;

		long val = buffer[pos] & 0xFF;
		for (int b = 0; b < numBytes; b++) val = (val << 8) + (buffer[--pos] & 0xFF);

		return val;
	}

	/**
	 * Writes a little-endian value into a byte buffer.
	 *
	 * @param val      the value to write
	 * @param buffer   the byte array to write into
	 * @param pos      the starting position in the buffer
	 * @param numBytes the number of bytes to write
	 */
	private static void putLE(long val, byte[] buffer, int pos, int numBytes) {
		for (int b = 0; b < numBytes; b++) {
			buffer[pos] = (byte) (val & 0xFF);
			val >>= 8;
			pos++;
		}
	}

	/**
	 * Writes a single sample value into the output buffer, flushing when the buffer is full.
	 *
	 * @param val the sample value to write
	 * @throws IOException if the output stream encounters an error
	 */
	private void writeSample(long val) throws IOException {
		for (int b = 0; b < bytesPerSample; b++) {
			if (bufferPointer == BUFFER_SIZE) {
				oStream.write(buffer, 0, BUFFER_SIZE);
				bufferPointer = 0;
			}

			buffer[bufferPointer] = (byte) (val & 0xFF);
			val >>= 8;
			bufferPointer++;
		}
	}

	/**
	 * Reads a single sample value from the input buffer, refilling from the stream as needed.
	 *
	 * @return the raw sample value as a long
	 * @throws IOException if the input stream encounters an error or has insufficient data
	 */
	private long readSample() throws IOException {
		long val = 0;

		for (int b = 0; b < bytesPerSample; b++) {
			if (bufferPointer == bytesRead) {
				int read = iStream.read(buffer, 0, BUFFER_SIZE);
				if (read == -1) throw new IOException("Not enough data available");
				bytesRead = read;
				bufferPointer = 0;
			}

			int v = buffer[bufferPointer];
			if (b < bytesPerSample - 1 || bytesPerSample == 1) v &= 0xFF;
			val += (long) v << (b * 8);

			bufferPointer++;
		}

		return val;
	}

	/**
	 * Reads frames into a flat int array, interleaving channels, starting at index 0.
	 *
	 * @param sampleBuffer   the buffer to receive interleaved integer samples
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(int[] sampleBuffer, int numFramesToRead) throws IOException {
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	/**
	 * Reads frames into a flat int array, interleaving channels, starting at the given offset.
	 *
	 * @param sampleBuffer    the buffer to receive interleaved integer samples
	 * @param offset          the starting index in the buffer
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(int[] sampleBuffer, int offset, int numFramesToRead) throws IOException {
		if (readerState != ReaderState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f = 0; f < numFramesToRead; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				sampleBuffer[offset] = (int) readSample();
				offset++;
			}

			frameCounter++;
		}

		return numFramesToRead;
	}

	/**
	 * Reads frames into a channel-indexed int array, starting at frame index 0.
	 *
	 * @param sampleBuffer    the buffer indexed as [channel][frame]
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(int[][] sampleBuffer, int numFramesToRead) throws IOException {
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	/**
	 * Reads frames into a channel-indexed int array, starting at the given frame offset.
	 *
	 * @param sampleBuffer    the buffer indexed as [channel][frame]
	 * @param offset          the starting frame index in the buffer
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(int[][] sampleBuffer, int offset, int numFramesToRead) throws IOException {
		if (readerState != ReaderState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f = 0; f < numFramesToRead; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) sampleBuffer[c][offset] = (int) readSample();

			offset++;
			frameCounter++;
		}

		return numFramesToRead;
	}

	/**
	 * Writes frames from a flat int array, interleaving channels, starting at index 0.
	 *
	 * @param sampleBuffer    the interleaved integer samples to write
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(int[] sampleBuffer, int numFramesToWrite) throws IOException {
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	/**
	 * Writes frames from a flat int array, interleaving channels, starting at the given offset.
	 *
	 * @param sampleBuffer     the interleaved integer samples to write
	 * @param offset           the starting index in the buffer
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(int[] sampleBuffer, int offset, int numFramesToWrite) throws IOException {
		if (readerState != ReaderState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f = 0; f < numFramesToWrite; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				writeSample(sampleBuffer[offset]);
				offset++;
			}

			frameCounter++;
		}

		return numFramesToWrite;
	}

	/**
	 * Writes frames from a channel-indexed int array, starting at frame index 0.
	 *
	 * @param sampleBuffer     the samples indexed as [channel][frame]
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(int[][] sampleBuffer, int numFramesToWrite) throws IOException {
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	/**
	 * Writes frames from a channel-indexed int array, starting at the given frame offset.
	 *
	 * @param sampleBuffer     the samples indexed as [channel][frame]
	 * @param offset           the starting frame index in the buffer
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(int[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException {
		if (readerState != ReaderState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f = 0; f < numFramesToWrite; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) writeSample(sampleBuffer[c][offset]);

			offset++;
			frameCounter++;
		}

		return numFramesToWrite;
	}

	/**
	 * Reads frames into a flat long array, interleaving channels, starting at index 0.
	 *
	 * @param sampleBuffer    the buffer to receive interleaved long samples
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(long[] sampleBuffer, int numFramesToRead) throws IOException {
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	/**
	 * Reads frames into a flat long array, interleaving channels, starting at the given offset.
	 *
	 * @param sampleBuffer    the buffer to receive interleaved long samples
	 * @param offset          the starting index in the buffer
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(long[] sampleBuffer, int offset, int numFramesToRead) throws IOException {
		if (readerState != ReaderState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f = 0; f < numFramesToRead; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				sampleBuffer[offset] = readSample();
				offset++;
			}

			frameCounter++;
		}

		return numFramesToRead;
	}

	/**
	 * Reads frames into a channel-indexed long array, starting at frame index 0.
	 *
	 * @param sampleBuffer    the buffer indexed as [channel][frame]
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(long[][] sampleBuffer, int numFramesToRead) throws IOException {
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	/**
	 * Reads frames into a channel-indexed long array, starting at the given frame offset.
	 *
	 * @param sampleBuffer    the buffer indexed as [channel][frame]
	 * @param offset          the starting frame index in the buffer
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(long[][] sampleBuffer, int offset, int numFramesToRead) throws IOException {
		if (readerState != ReaderState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f = 0; f < numFramesToRead; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) sampleBuffer[c][offset] = readSample();

			offset++;
			frameCounter++;
		}

		return numFramesToRead;
	}

	/**
	 * Writes frames from a flat long array, interleaving channels, starting at index 0.
	 *
	 * @param sampleBuffer     the interleaved long samples to write
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(long[] sampleBuffer, int numFramesToWrite) throws IOException {
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	/**
	 * Writes frames from a flat long array, interleaving channels, starting at the given offset.
	 *
	 * @param sampleBuffer     the interleaved long samples to write
	 * @param offset           the starting index in the buffer
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(long[] sampleBuffer, int offset, int numFramesToWrite) throws IOException {
		if (readerState != ReaderState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f = 0; f < numFramesToWrite; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				writeSample(sampleBuffer[offset]);
				offset++;
			}

			frameCounter++;
		}

		return numFramesToWrite;
	}

	/**
	 * Writes frames from a channel-indexed long array, starting at frame index 0.
	 *
	 * @param sampleBuffer     the samples indexed as [channel][frame]
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(long[][] sampleBuffer, int numFramesToWrite) throws IOException {
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	/**
	 * Writes frames from a channel-indexed long array, starting at the given frame offset.
	 *
	 * @param sampleBuffer     the samples indexed as [channel][frame]
	 * @param offset           the starting frame index in the buffer
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(long[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException {
		if (readerState != ReaderState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f = 0; f < numFramesToWrite; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) writeSample(sampleBuffer[c][offset]);

			offset++;
			frameCounter++;
		}

		return numFramesToWrite;
	}

	/**
	 * Reads frames as normalized doubles [-1.0, 1.0] into a flat array, starting at index 0.
	 *
	 * @param sampleBuffer    the buffer to receive interleaved normalized double samples
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(double[] sampleBuffer, int numFramesToRead) throws IOException {
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	/**
	 * Reads frames as normalized doubles [-1.0, 1.0] into a flat array, starting at the given offset.
	 *
	 * @param sampleBuffer    the buffer to receive interleaved normalized double samples
	 * @param offset          the starting index in the buffer
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(double[] sampleBuffer, int offset, int numFramesToRead) throws IOException {
		if (readerState != ReaderState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f = 0; f < numFramesToRead; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				sampleBuffer[offset] = floatOffset + (double) readSample() / floatScale;
				offset++;
			}

			frameCounter++;
		}

		return numFramesToRead;
	}

	/**
	 * Reads frames as normalized doubles [-1.0, 1.0] into a channel-indexed array, starting at frame 0.
	 *
	 * @param sampleBuffer    the buffer indexed as [channel][frame]
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(double[][] sampleBuffer, int numFramesToRead) throws IOException {
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	/**
	 * Reads frames as normalized doubles [-1.0, 1.0] into a channel-indexed array,
	 * starting at the given frame offset.
	 *
	 * @param sampleBuffer    the buffer indexed as [channel][frame]
	 * @param offset          the starting frame index in the buffer
	 * @param numFramesToRead the number of frames to read
	 * @return the number of frames actually read
	 * @throws IOException if the file is not open for reading
	 */
	public int readFrames(double[][] sampleBuffer, int offset, int numFramesToRead) throws IOException {
		if (readerState != ReaderState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f = 0; f < numFramesToRead; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				sampleBuffer[c][offset] = floatOffset + (double) readSample() / floatScale;
			}

			offset++;
			frameCounter++;
		}

		return numFramesToRead;
	}

	/**
	 * Writes frames from a flat normalized double array, starting at index 0.
	 *
	 * @param sampleBuffer     the interleaved normalized double samples to write
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(double[] sampleBuffer, int numFramesToWrite) throws IOException {
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	/**
	 * Writes frames from a flat normalized double array, starting at the given offset.
	 *
	 * @param sampleBuffer     the interleaved normalized double samples to write
	 * @param offset           the starting index in the buffer
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(double[] sampleBuffer, int offset, int numFramesToWrite) throws IOException {
		if (readerState != ReaderState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f = 0; f < numFramesToWrite; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				writeSample((long) (floatScale * (floatOffset + sampleBuffer[offset])));
				offset++;
			}

			frameCounter++;
		}

		return numFramesToWrite;
	}

	/**
	 * Writes all frames from a channel-indexed normalized double array.
	 *
	 * @param sampleBuffer the samples indexed as [channel][frame]; all frames are written
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(double[][] sampleBuffer) throws IOException {
		return writeFrames(sampleBuffer, sampleBuffer[0].length);
	}

	/**
	 * Writes frames from a channel-indexed normalized double array, starting at frame index 0.
	 *
	 * @param sampleBuffer     the samples indexed as [channel][frame]
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(double[][] sampleBuffer, int numFramesToWrite) throws IOException {
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	/**
	 * Writes frames from a channel-indexed normalized double array, starting at the given offset.
	 *
	 * @param sampleBuffer     the samples indexed as [channel][frame]
	 * @param offset           the starting frame index in the buffer
	 * @param numFramesToWrite the number of frames to write
	 * @return the number of frames actually written
	 * @throws IOException if the file is not open for writing
	 */
	public int writeFrames(double[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException {
		if (readerState != ReaderState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f = 0; f < numFramesToWrite; f++) {
			if (frameCounter == numFrames) return f;

			for (int c = 0; c < numChannels; c++) {
				writeSample((long) (floatScale * (floatOffset + sampleBuffer[c][offset])));
			}

			offset++;
			frameCounter++;
		}

		return numFramesToWrite;
	}

	@Override
	public void close() throws IOException {
		// Close the input stream and set to null
		if (iStream != null) {
			iStream.close();
			iStream = null;
		}

		if (oStream != null) {
			// Write out anything still in the local buffer
			if (bufferPointer > 0) oStream.write(buffer, 0, bufferPointer);

			// If an extra byte is required for word alignment, add it to the end
			if (wordAlignAdjust) oStream.write(0);

			// Close the stream and set to null
			oStream.close();
			oStream = null;
		}

		// Flag that the stream is closed
		readerState = ReaderState.CLOSED;
	}

	/**
	 * Prints WAV file information to standard output.
	 */
	public void display() {
		display(System.out);
	}

	/**
	 * Prints WAV file information to the specified PrintStream.
	 *
	 * @param out the PrintStream to write to
	 */
	public void display(PrintStream out) {
		if (file != null) out.printf("File: %s\n", file);
		out.printf("Channels: %d, Frames: %d\n", numChannels, numFrames);
		out.printf("IO State: %s\n", readerState);
		out.printf("Sample Rate: %d, Block Align: %d\n", sampleRate, blockAlign);
		out.printf("Valid Bits: %d, Bytes per sample: %d\n", validBits, bytesPerSample);
	}
}
