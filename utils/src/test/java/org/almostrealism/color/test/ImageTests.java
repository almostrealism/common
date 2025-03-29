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

package org.almostrealism.color.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class ImageTests implements RGBFeatures, TestFeatures {
	protected CollectionProducer<PackedCollection<?>> imageTransform(CollectionProducer<PackedCollection<?>> image) {
		return image.multiply(2).subtract(1.0);
	}

	protected CollectionProducer<PackedCollection<?>> imageTransformReverse(Producer<PackedCollection<?>> data) {
		return c(data).add(1.0).divide(2);
	}

	@Test
	public void noise() throws IOException {
		CollectionProducer<PackedCollection<?>> data = imageTransformReverse(randn(shape(3, 128, 128)));
		saveChannels("results/noise.png", data).get().run();
	}

	@Test
	public void addNoise() throws IOException {
		File img = new File("library/test_image.jpeg");
		if (!img.exists() && skipKnownIssues) return;

		CollectionProducer<PackedCollection<?>> data =
				imageTransform(channels(img));

		log(data.getShape());
		Producer<PackedCollection<?>> random = randn(data.getShape());
		Assert.assertEquals(data.getShape(), shape(random));

		double level = 0.3;
		data = data.multiply(c(1.0 - level))
				.add(c(level).multiply(random));
		saveChannels("results/noise_add.png", data).get().run();
	}
}
