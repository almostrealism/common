/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.collect.PackedCollection;

/**
 * Extracts a volume envelope from audio by computing a moving average of absolute values.
 *
 * <p>VolumeEnvelopeExtraction implements a simple envelope follower that takes the
 * absolute value of the input signal and applies a moving average filter to smooth
 * the result. This is useful for dynamics processing and audio analysis.</p>
 *
 * @see StatelessFilter
 */
public class VolumeEnvelopeExtraction implements StatelessFilter, CodeFeatures {
	private final PackedCollection coefficients;

	public VolumeEnvelopeExtraction() {
		this(281);
	}

	public VolumeEnvelopeExtraction(int aggregationWidth) {
		coefficients = new PackedCollection(aggregationWidth);
		coefficients.fill(1.0 / aggregationWidth);
	}

	@Override
	public Producer<PackedCollection> filter(BufferDetails buffer,
												Producer<PackedCollection> params,
												Producer<PackedCollection> input) {
		return aggregate(abs(input), cp(coefficients));
	}
}
