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

package org.almostrealism.color.computations;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.computations.RankedChoiceEvaluableForMemoryData;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.NullProcessor;

import java.util.function.IntFunction;

/**
 * A {@link RankedChoiceEvaluableForMemoryData} specialization that produces {@link RGB}-compatible
 * memory banks and handles null results as black when configured to tolerate nulls.
 *
 * <p>This evaluable selects among multiple candidate producers based on ranked choice logic
 * inherited from {@code RankedChoiceEvaluableForMemoryData}. When no candidate produces a
 * non-null result and {@code tolerateNull} is {@code true}, a black {@link RGB} value is
 * returned instead of throwing a {@link NullPointerException}.</p>
 *
 * @see RankedChoiceEvaluableForMemoryData
 * @see NullProcessor
 * @author Michael Murray
 */
public class RankedChoiceEvaluableForRGB extends RankedChoiceEvaluableForMemoryData<PackedCollection> implements NullProcessor<PackedCollection>, RGBFeatures {
	/**
	 * Constructs a {@link RankedChoiceEvaluableForRGB} with the given error tolerance
	 * and default null-intolerance (nulls throw {@link NullPointerException}).
	 *
	 * @param e the error tolerance used in ranked choice selection
	 */
	public RankedChoiceEvaluableForRGB(double e) {
		super(e);
	}

	/**
	 * Constructs a {@link RankedChoiceEvaluableForRGB} with the given error tolerance
	 * and configurable null tolerance.
	 *
	 * @param e            the error tolerance used in ranked choice selection
	 * @param tolerateNull {@code true} to return black instead of throwing on null results
	 */
	public RankedChoiceEvaluableForRGB(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	/**
	 * Returns a hardware-accelerated {@link Evaluable} that operates over 3-component
	 * {@link RGB} memory banks.
	 *
	 * @return an accelerated evaluable using {@link RGB} constructors and banks
	 */
	public Evaluable<PackedCollection> getAccelerated() {
		return getAccelerated(3, RGB::new, (IntFunction) RGB::bank);
	}

	/**
	 * Returns a replacement value when a null result is encountered.
	 *
	 * <p>If {@code tolerateNull} is {@code true}, a black {@link RGB} is returned.
	 * Otherwise a {@link NullPointerException} is thrown.</p>
	 *
	 * @param args the evaluation arguments (unused in the replacement logic)
	 * @return a black {@link RGB} when null is tolerated
	 * @throws NullPointerException if null tolerance is disabled
	 */
	@Override
	public PackedCollection replaceNull(Object[] args) {
		if (tolerateNull) {
			return black().get().evaluate();
		} else {
			throw new NullPointerException();
		}
	}

	/**
	 * Creates an {@link RGB} memory bank of the given size as the output destination.
	 *
	 * @param size the number of RGB elements to allocate
	 * @return an {@link RGB} bank of the specified size
	 */
	@Override
	public MemoryBank<PackedCollection> createDestination(int size) { return (MemoryBank) RGB.bank(size); }
}
