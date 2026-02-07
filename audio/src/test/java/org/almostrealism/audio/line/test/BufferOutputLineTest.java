/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.line.BufferOutputLine;
import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link BufferOutputLine} functionality.
 */
public class BufferOutputLineTest extends TestSuiteBase implements AudioTestFeatures {

	@Test
	public void testDefaultConstruction() {
		BufferOutputLine buffer = new BufferOutputLine(1024);

		assertEquals(1024, buffer.getBufferSize());
		assertEquals(OutputLine.sampleRate, buffer.getSampleRate());
		assertEquals(0, buffer.getTotalFramesWritten());
		assertEquals(0, buffer.getWritePosition());
		assertFalse(buffer.isActive());
		assertTrue(buffer.isCircular());
	}

	@Test
	public void testCustomSampleRate() {
		BufferOutputLine buffer = new BufferOutputLine(1024, 48000);

		assertEquals(1024, buffer.getBufferSize());
		assertEquals(48000, buffer.getSampleRate());
	}

	@Test
	public void testWriteCapturesData() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		PackedCollection samples = new PackedCollection(10);
		for (int i = 0; i < 10; i++) {
			samples.setMem(i, i * 0.1);
		}

		buffer.write(samples);

		assertEquals(10, buffer.getTotalFramesWritten());
		assertEquals(10, buffer.getWritePosition());

		for (int i = 0; i < 10; i++) {
			assertEquals(i * 0.1, buffer.getSample(i), 0.0001);
		}
	}

	@Test
	public void testCircularBufferWraps() {
		BufferOutputLine buffer = new BufferOutputLine(10);

		PackedCollection samples = new PackedCollection(15);
		for (int i = 0; i < 15; i++) {
			samples.setMem(i, i + 1.0);
		}

		buffer.write(samples);

		assertEquals(15, buffer.getTotalFramesWritten());
		assertEquals(5, buffer.getWritePosition());

		assertEquals(11.0, buffer.getSample(0), 0.0001);
		assertEquals(12.0, buffer.getSample(1), 0.0001);
		assertEquals(15.0, buffer.getSample(4), 0.0001);

		assertEquals(6.0, buffer.getSample(5), 0.0001);
		assertEquals(10.0, buffer.getSample(9), 0.0001);
	}

	@Test
	public void testNonCircularBufferStops() {
		BufferOutputLine buffer = new BufferOutputLine(10);
		buffer.setCircular(false);

		PackedCollection samples = new PackedCollection(15);
		for (int i = 0; i < 15; i++) {
			samples.setMem(i, i + 1.0);
		}

		buffer.write(samples);

		assertEquals(10, buffer.getWritePosition());
		assertEquals(10, buffer.getTotalFramesWritten());

		assertEquals(1.0, buffer.getSample(0), 0.0001);
		assertEquals(10.0, buffer.getSample(9), 0.0001);
	}

	@Test
	public void testGetCapturedAudio() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		PackedCollection samples = new PackedCollection(50);
		for (int i = 0; i < 50; i++) {
			samples.setMem(i, i * 0.01);
		}

		buffer.write(samples);

		PackedCollection captured = buffer.getCapturedAudio();

		assertEquals(50, captured.getMemLength());
		for (int i = 0; i < 50; i++) {
			assertEquals(i * 0.01, captured.toDouble(i), 0.0001);
		}
	}

	@Test
	public void testHasAudio() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		assertFalse(buffer.hasAudio());

		PackedCollection zeros = new PackedCollection(10);
		buffer.write(zeros);
		assertFalse(buffer.hasAudio());

		buffer.reset();

		PackedCollection nonZero = new PackedCollection(10);
		nonZero.setMem(5, 0.5);
		buffer.write(nonZero);
		assertTrue(buffer.hasAudio());
	}

	@Test
	public void testPeakAmplitude() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		PackedCollection samples = new PackedCollection(10);
		samples.setMem(3, 0.5);
		samples.setMem(7, -0.8);

		buffer.write(samples);

		assertEquals(0.8, buffer.getPeakAmplitude(), 0.0001);
	}

	@Test
	public void testRmsAmplitude() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		PackedCollection samples = new PackedCollection(4);
		samples.setMem(0, 1.0);
		samples.setMem(1, -1.0);
		samples.setMem(2, 1.0);
		samples.setMem(3, -1.0);

		buffer.write(samples);

		assertEquals(1.0, buffer.getRmsAmplitude(), 0.0001);
	}

	@Test
	public void testZeroCrossings() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		PackedCollection samples = new PackedCollection(8);
		samples.setMem(0, 1.0);
		samples.setMem(1, 0.5);
		samples.setMem(2, -0.5);
		samples.setMem(3, -1.0);
		samples.setMem(4, -0.5);
		samples.setMem(5, 0.5);
		samples.setMem(6, 1.0);
		samples.setMem(7, 0.5);

		buffer.write(samples);

		assertEquals(2, buffer.countZeroCrossings());
	}

	@Test
	public void testEstimateFrequency() {
		int sampleRate = 44100;
		BufferOutputLine buffer = new BufferOutputLine(sampleRate, sampleRate);

		PackedCollection sine = generateTestSine(440.0, 1.0, 0.1, sampleRate);
		buffer.write(sine);

		double estimated = buffer.estimateFrequency();
		assertEquals(440.0, estimated, 10.0);
	}

	@Test
	public void testStartStop() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		assertFalse(buffer.isActive());

		buffer.start();
		assertTrue(buffer.isActive());

		buffer.stop();
		assertFalse(buffer.isActive());
	}

	@Test
	public void testReset() {
		BufferOutputLine buffer = new BufferOutputLine(100);

		PackedCollection samples = new PackedCollection(50);
		for (int i = 0; i < 50; i++) {
			samples.setMem(i, 0.5);
		}
		buffer.write(samples);

		assertEquals(50, buffer.getTotalFramesWritten());
		assertEquals(50, buffer.getWritePosition());

		buffer.reset();

		assertEquals(0, buffer.getTotalFramesWritten());
		assertEquals(0, buffer.getWritePosition());
		assertFalse(buffer.hasAudio());
	}

	@Test
	public void testDestroy() {
		BufferOutputLine buffer = new BufferOutputLine(100);
		buffer.start();

		PackedCollection samples = new PackedCollection(50);
		buffer.write(samples);

		buffer.destroy();

		assertFalse(buffer.isActive());
		assertEquals(0, buffer.getTotalFramesWritten());
		assertEquals(0, buffer.getWritePosition());
	}

	@Test
	public void testDurationCalculation() {
		BufferOutputLine buffer = new BufferOutputLine(44100, 44100);

		PackedCollection samples = new PackedCollection(22050);
		buffer.write(samples);

		assertEquals(0.5, buffer.getDurationWritten(), 0.001);
	}

	@Test
	public void testLineUtilitiesGetBufferLine() {
		BufferOutputLine buffer = LineUtilities.getBufferLine(2048);
		assertNotNull(buffer);
		assertEquals(2048, buffer.getBufferSize());
	}

	@Test
	public void testLineUtilitiesGetBufferLineDefault() {
		BufferOutputLine buffer = LineUtilities.getBufferLine();
		assertNotNull(buffer);
		assertEquals(OutputLine.sampleRate, buffer.getBufferSize());
	}

	@Test
	public void testAudioTestFeaturesAssertions() {
		BufferOutputLine buffer = bufferOutput(44100);

		PackedCollection sine = generateTestSine(440.0, 0.8, 0.1);
		buffer.write(sine);

		assertHasAudio(buffer);
		assertNoClipping(buffer);
		assertPeakAmplitudeInRange(buffer, 0.7, 0.9);
		assertFrequencyApprox(buffer, 440.0, 20.0);
		assertMinFramesWritten(buffer, 4000);
		assertMinDurationWritten(buffer, 0.09);
	}

	@Test
	public void testCorrelationWithGeneratedSine() {
		PackedCollection reference = generateTestSine(440.0, 1.0, 0.1);
		PackedCollection similar = generateTestSine(440.0, 1.0, 0.1);
		PackedCollection different = generateTestSine(880.0, 1.0, 0.1);

		double sameCorrelation = computeCorrelation(reference, similar);
		assertTrue("Same signals should have high correlation",
				sameCorrelation > 0.99);

		double diffCorrelation = computeCorrelation(reference, different);
		assertTrue("Different frequencies should have lower correlation",
				Math.abs(diffCorrelation) < sameCorrelation);
	}

	@Test
	public void testAssertAudioSimilar() {
		PackedCollection reference = generateTestSine(440.0, 1.0, 0.1);
		PackedCollection similar = generateTestSine(440.0, 1.0, 0.1);

		assertAudioSimilar(reference, similar, 0.99);
	}
}
