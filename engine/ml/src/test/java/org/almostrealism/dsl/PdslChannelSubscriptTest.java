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

package org.almostrealism.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests of the per-channel subscript {@code bank[index]} on a collection-valued argument, which
 * carves channel {@code index} out of a bank holding {@code channels} equal slices. The slice is
 * defined by the bank's elements, so it must not depend on the traversal axis the bank's shape
 * happens to carry: a bank traversed at its element axis holds the same values in the same order
 * as one traversed at axis zero, and selects the same slice.
 */
public class PdslChannelSubscriptTest extends TestSuiteBase {

	/** Fixture declaring {@code channel_gain}, the layer that scales its input by the selected factor. */
	private static final String FIXTURE = "/pdsl/test_channel_subscript.pdsl";

	/** Largest absolute difference permitted from the host reference. */
	private static final double TOLERANCE = 1e-6;

	/** The number of channels in the bank. */
	private static final int CHANNELS = 3;

	/** A bank with its shape at the default traversal axis selects each channel's factor. */
	@Test(timeout = 120000)
	public void subscriptSelectsChannelOfBank() {
		for (int index = 0; index < CHANNELS; index++) {
			assertChannelGain(bank(), index);
		}
	}

	/**
	 * A bank whose shape is traversed at its element axis, as the result of an element-wise
	 * computation commonly is, selects the same factor for each channel as the untraversed bank.
	 */
	@Test(timeout = 120000)
	public void subscriptSelectsChannelOfTraversedBank() {
		for (int index = 0; index < CHANNELS; index++) {
			assertChannelGain(bank().traverse(1), index);
		}
	}

	/**
	 * A bank holding fewer elements than there are channels is broadcast: every channel reads the
	 * same single factor rather than failing on a zero-length slice.
	 */
	@Test(timeout = 120000)
	public void subscriptBroadcastsSingleValueBank() {
		for (int index = 0; index < CHANNELS; index++) {
			assertChannelGain(PackedCollection.of(7.0), index, 7.0);
		}
	}

	/**
	 * The per-channel factors, one distinct value for each of the {@link #CHANNELS} channels.
	 *
	 * @return the bank, shaped {@code [CHANNELS]} at the default traversal axis
	 */
	private PackedCollection bank() {
		return PackedCollection.of(2.0, 3.0, 5.0);
	}

	/**
	 * Builds {@code channel_gain} for one channel of the bank, forwards a fixed input and asserts
	 * every output element is the input times that channel's factor.
	 *
	 * @param volume the bank of per-channel factors
	 * @param index  the channel to select
	 */
	private void assertChannelGain(PackedCollection volume, int index) {
		assertChannelGain(volume, index, bank().doubleStream().toArray()[index]);
	}

	/**
	 * Builds {@code channel_gain} for one channel of the bank, forwards a fixed input and asserts
	 * every output element is the input times the expected factor.
	 *
	 * @param volume the bank of per-channel factors
	 * @param index  the channel to select
	 * @param factor the factor the selected channel is expected to apply
	 */
	private void assertChannelGain(PackedCollection volume, int index, double factor) {
		TraversalPolicy inputShape = shape(1, 4);
		PackedCollection input = PackedCollection.of(1.0, -0.5, 0.25, 4.0).reshape(inputShape);

		Map<String, Object> args = new HashMap<>();
		args.put("channels", CHANNELS);
		args.put("index", index);
		args.put("volume", volume);

		PdslLoader loader = new PdslLoader();
		Block block = loader.buildLayer(loader.parseResource(FIXTURE), "channel_gain", inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		try (CompiledModel compiled = model.compile(false)) {
			double[] in = input.doubleStream().toArray();
			double[] actual = compiled.forward(input).doubleStream().toArray();
			Assert.assertEquals("output length", in.length, actual.length);
			for (int i = 0; i < in.length; i++) {
				Assert.assertEquals("channel " + index + " element " + i,
						in[i] * factor, actual[i], TOLERANCE);
			}
		}
	}
}
