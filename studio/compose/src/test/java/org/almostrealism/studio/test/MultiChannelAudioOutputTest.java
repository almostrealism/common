/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.graph.Receptor;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tests the routing of stereo channels to the {@link WaveOutput}s behind a
 * {@link MultiChannelAudioOutput}, in particular that a mono destination
 * exposes no receptor for the channel it does not have.
 */
public class MultiChannelAudioOutputTest extends TestSuiteBase implements CellFeatures {
	/** Timeline length of the outputs under test; small, since nothing is rendered. */
	private static final int FRAMES = 1024;

	/**
	 * Creates a file-less output with the given channel layout.
	 *
	 * @param stereo {@code true} for two channels, {@code false} for one
	 * @return the output
	 */
	private WaveOutput output(boolean stereo) {
		return new WaveOutput(() -> null, 24, OutputLine.sampleRate, FRAMES, stereo);
	}

	/**
	 * A mono master has a receptor for the left channel only.
	 */
	@Test(timeout = 60000)
	public void monoMasterHasLeftReceptorOnly() {
		try (WaveOutput master = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master);
			Assert.assertNotNull(out.getMaster(ChannelInfo.StereoChannel.LEFT));
			Assert.assertNull(out.getMaster(ChannelInfo.StereoChannel.RIGHT));
		}
	}

	/**
	 * A stereo master has a receptor for both channels.
	 */
	@Test(timeout = 60000)
	public void stereoMasterHasBothReceptors() {
		try (WaveOutput master = output(true)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master);
			Assert.assertNotNull(out.getMaster(ChannelInfo.StereoChannel.LEFT));
			Assert.assertNotNull(out.getMaster(ChannelInfo.StereoChannel.RIGHT));
		}
	}

	/**
	 * Mono stems follow the same rule as the master: the right channel of a
	 * pattern channel's stem is absent rather than an error.
	 */
	@Test(timeout = 60000)
	public void monoStemsHaveLeftReceptorOnly() {
		try (WaveOutput master = output(false); WaveOutput stem = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master, List.of(stem));
			Assert.assertNotNull(out.getStem(0, ChannelInfo.StereoChannel.LEFT));
			Assert.assertNull(out.getStem(0, ChannelInfo.StereoChannel.RIGHT));
		}
	}

	/**
	 * When measure monitoring is disabled, {@link MultiChannelAudioOutput#getMeasures}
	 * must return an empty list rather than dereferencing the absent backing map, so
	 * callers that build a destination list (e.g. {@code MixdownManager.createEfx}'s
	 * {@code disableClean} branch) don't fail before reaching the null-filtering
	 * Receptor factories.
	 */
	@Test(timeout = 60000)
	public void measuresInactiveReturnsEmptyList() {
		try (WaveOutput master = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master);
			Assert.assertFalse(out.isMeasuresActive());
			Assert.assertTrue(out.getMeasures(ChannelInfo.StereoChannel.LEFT).isEmpty());
		}
	}

	/**
	 * With a measures factory supplied, each distinct voicing/stereo-channel pair is
	 * resolved to its own receptor, repeated lookups return the cached instance, and
	 * {@link MultiChannelAudioOutput#getMeasures} returns only the receptors matching
	 * the requested stereo channel. A push then covers both present stereo channels.
	 */
	@Test(timeout = 60000)
	public void measuresActiveRoutePerStereoChannel() {
		MultiChannelAudioOutput out = new MultiChannelAudioOutput(
				(Function<ChannelInfo.StereoChannel, Receptor<PackedCollection>>) null, null,
				channel -> protein -> new OperationList(channel.toString()));

		Assert.assertTrue(out.isMeasuresActive());

		Receptor<PackedCollection> left =
				out.getMeasure(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
		Assert.assertNotNull(left);
		Assert.assertSame("a repeated lookup returns the cached receptor", left,
				out.getMeasure(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT));

		Receptor<PackedCollection> right =
				out.getMeasure(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.RIGHT);
		Assert.assertNotSame("each stereo channel resolves to its own receptor", left, right);

		List<Receptor<PackedCollection>> leftMeasures =
				out.getMeasures(ChannelInfo.StereoChannel.LEFT);
		Assert.assertEquals(1, leftMeasures.size());
		Assert.assertSame(left, leftMeasures.get(0));

		Producer<PackedCollection> frame = c(0.0);
		Assert.assertEquals("a push covers both present stereo channels",
				2, ((OperationList) out.pushMeasures(ChannelInfo.Voicing.MAIN, frame)).size());
	}

	/**
	 * When two pattern channels share a voicing and stereo channel, the by-voicing
	 * measure lookup is ambiguous and must reject the request rather than silently
	 * returning one of the matches.
	 */
	@Test(timeout = 60000)
	public void ambiguousMeasureLookupIsRejected() {
		MultiChannelAudioOutput out = new MultiChannelAudioOutput(
				(Function<ChannelInfo.StereoChannel, Receptor<PackedCollection>>) null, null,
				channel -> protein -> new OperationList(channel.toString()));

		out.getMeasure(new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT));
		out.getMeasure(new ChannelInfo(1, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT));

		try {
			out.getMeasure(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
			Assert.fail("an ambiguous by-voicing lookup must be rejected");
		} catch (IllegalArgumentException expected) {
			// a single voicing/stereo pair must resolve to at most one receptor
		}
	}

	/**
	 * {@link MultiChannelAudioOutput#pushMaster} emits one push per present master
	 * receptor — two for a stereo master, one for a mono master, none when master
	 * output is disabled — so a stereo channel with no destination is skipped rather
	 * than pushed to a null receptor.
	 */
	@Test(timeout = 60000)
	public void pushMasterCoversPresentReceptorsOnly() {
		Producer<PackedCollection> frame = c(0.0);

		try (WaveOutput stereo = output(true)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(stereo);
			Assert.assertEquals(2, ((OperationList) out.pushMaster(frame)).size());
		}

		try (WaveOutput mono = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(mono);
			Assert.assertEquals(1, ((OperationList) out.pushMaster(frame)).size());
		}

		MultiChannelAudioOutput disabled = new MultiChannelAudioOutput();
		Assert.assertTrue(((OperationList) disabled.pushMaster(frame)).isEmpty());
	}

	/**
	 * {@link MultiChannelAudioOutput#pushStem} emits one push per present stem
	 * receptor for the given channel — one for a mono stem — and none when stem
	 * output is disabled.
	 */
	@Test(timeout = 60000)
	public void pushStemCoversPresentReceptorsOnly() {
		Producer<PackedCollection> frames = c(0.0);

		try (WaveOutput master = output(false); WaveOutput stem = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master, List.of(stem));
			Assert.assertEquals(1, ((OperationList) out.pushStem(0, frames)).size());

			MultiChannelAudioOutput noStems = new MultiChannelAudioOutput(master);
			Assert.assertTrue(((OperationList) noStems.pushStem(0, frames)).isEmpty());
		}
	}

	/**
	 * The per-side push overloads used by true stereo route each side's own frame to that
	 * side's receptor: the LEFT frame reaches only the LEFT receptor and the RIGHT frame
	 * only the RIGHT one, for the master, stem and measure families alike. A dual-mono
	 * caller would push one frame to both; this pins that the two sides are kept apart.
	 */
	@Test(timeout = 60000)
	public void perSidePushRoutesEachFrameToItsSide() {
		Map<String, Producer<PackedCollection>> received = new HashMap<>();
		MultiChannelAudioOutput out = new MultiChannelAudioOutput(
				side -> recorder(received, "master-" + side),
				info -> recorder(received, "stem" + info.getPatternChannel() + "-" + info.getAudioChannel()),
				info -> recorder(received, "measure-" + info.getVoicing()
						+ "-" + info.getAudioChannel()));

		Producer<PackedCollection> left = c(1.0);
		Producer<PackedCollection> right = c(-1.0);
		Function<ChannelInfo.StereoChannel, Producer<PackedCollection>> frame =
				side -> side == ChannelInfo.StereoChannel.LEFT ? left : right;

		Assert.assertEquals(2, ((OperationList) out.pushMaster(frame)).size());
		Assert.assertEquals(2, ((OperationList) out.pushStem(0, frame)).size());
		Assert.assertEquals(2, ((OperationList) out.pushMeasures(ChannelInfo.Voicing.WET, frame)).size());

		Assert.assertSame(left, received.get("master-LEFT"));
		Assert.assertSame(right, received.get("master-RIGHT"));
		Assert.assertSame(left, received.get("stem0-LEFT"));
		Assert.assertSame(right, received.get("stem0-RIGHT"));
		Assert.assertSame(left, received.get("measure-WET-LEFT"));
		Assert.assertSame(right, received.get("measure-WET-RIGHT"));
		Assert.assertEquals("no other receptor may be pushed to", 6, received.size());
	}

	/**
	 * With a mono master only the LEFT receptor exists, so the per-side push emits a single
	 * operation and never asks the frame function for the RIGHT side's frame — the absent
	 * side is skipped, not pushed to a null receptor.
	 */
	@Test(timeout = 60000)
	public void perSidePushSkipsAbsentSide() {
		List<ChannelInfo.StereoChannel> requested = new ArrayList<>();
		Function<ChannelInfo.StereoChannel, Producer<PackedCollection>> frame = side -> {
			requested.add(side);
			return c(0.0);
		};

		try (WaveOutput mono = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(mono);
			Assert.assertEquals(1, ((OperationList) out.pushMaster(frame)).size());
		}

		Assert.assertEquals(List.of(ChannelInfo.StereoChannel.LEFT), requested);
		Assert.assertTrue(((OperationList) new MultiChannelAudioOutput().pushMaster(frame)).isEmpty());
		Assert.assertEquals("a disabled master requests no frame", 1, requested.size());
	}

	/**
	 * Returns a receptor that records the producer it is asked to push under {@code key}.
	 *
	 * @param received the map the pushed producers are recorded in
	 * @param key      the key identifying this receptor
	 * @return the recording receptor
	 */
	private static Receptor<PackedCollection> recorder(Map<String, Producer<PackedCollection>> received,
													   String key) {
		return protein -> {
			received.put(key, protein);
			return new OperationList(key);
		};
	}
}
