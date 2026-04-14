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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.line.ChannelPairView;
import org.almostrealism.audio.line.MultiChannelOutputLine;
import org.almostrealism.collect.PackedCollection;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Tests for {@link MultiChannelOutputLine} and {@link ChannelPairView}.
 */
public class MultiChannelOutputLineTest {

	@Test
	public void channelPairViewLabel() {
		// Create a minimal multi-channel line for testing pair views
		MultiChannelOutputLine mcLine = createMockMultiChannelLine(8);
		Assume.assumeNotNull(mcLine);

		ChannelPairView view0 = mcLine.getView(0);
		ChannelPairView view1 = mcLine.getView(1);
		ChannelPairView view2 = mcLine.getView(2);
		ChannelPairView view3 = mcLine.getView(3);

		Assert.assertEquals("1-2", view0.getPairLabel());
		Assert.assertEquals("3-4", view1.getPairLabel());
		Assert.assertEquals("5-6", view2.getPairLabel());
		Assert.assertEquals("7-8", view3.getPairLabel());
	}

	@Test
	public void pairCountCalculation() {
		MultiChannelOutputLine mcLine = createMockMultiChannelLine(8);
		Assume.assumeNotNull(mcLine);

		Assert.assertEquals(4, mcLine.getPairCount());
		Assert.assertEquals(8, mcLine.getOutputChannels());
	}

	@Test(expected = IllegalArgumentException.class)
	public void invalidPairIndexThrows() {
		MultiChannelOutputLine mcLine = createMockMultiChannelLine(4);
		Assume.assumeNotNull(mcLine);

		mcLine.getView(2); // only pairs 0 and 1 exist
	}

	@Test
	public void viewBufferSizeMatchesParent() {
		MultiChannelOutputLine mcLine = createMockMultiChannelLine(8);
		Assume.assumeNotNull(mcLine);

		ChannelPairView view = mcLine.getView(0);
		Assert.assertEquals(mcLine.getBufferSize(), view.getBufferSize());
	}

	@Test
	public void viewDestroyDoesNotDestroyParent() {
		MultiChannelOutputLine mcLine = createMockMultiChannelLine(4);
		Assume.assumeNotNull(mcLine);

		ChannelPairView view = mcLine.getView(0);
		view.destroy(); // should be a no-op

		// Parent should still be functional
		Assert.assertEquals(2, mcLine.getPairCount());
	}

	/**
	 * Creates a MultiChannelOutputLine backed by a real SourceDataLine
	 * with the specified channel count, or returns null if hardware
	 * doesn't support it.
	 */
	private MultiChannelOutputLine createMockMultiChannelLine(int channels) {
		int frameSize = channels * 2;
		AudioFormat format = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED, 44100, 16,
				channels, frameSize, 44100, false);
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

		if (!AudioSystem.isLineSupported(info)) return null;

		try {
			SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format, 4096);
			return new MultiChannelOutputLine(line, channels, 1024);
		} catch (LineUnavailableException e) {
			return null;
		}
	}
}
