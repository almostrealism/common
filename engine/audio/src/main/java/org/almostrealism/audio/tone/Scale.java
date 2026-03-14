/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.tone;

import io.almostrealism.uml.Plural;

import java.util.function.Consumer;

/**
 * A musical scale represented as a collection of key positions.
 *
 * <p>Scale extends {@link Plural} to provide ordered access to notes in a musical scale.
 * Scales can be created using the static factory method {@link #of(KeyPosition[])} or
 * via {@link WesternScales} for common Western scales.</p>
 *
 * <h2>Creating Scales</h2>
 * <pre>{@code
 * // Create a custom scale
 * Scale<WesternChromatic> cMajorTriad = Scale.of(
 *     WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);
 *
 * // Use factory methods for standard scales
 * Scale<WesternChromatic> cMajor = WesternScales.major(WesternChromatic.C4, 1);
 * Scale<WesternChromatic> aMinor = WesternScales.minor(WesternChromatic.A3, 1);
 * }</pre>
 *
 * <h2>Iterating Notes</h2>
 * <pre>{@code
 * KeyboardTuning tuning = new DefaultKeyboardTuning();
 * Scale<WesternChromatic> scale = WesternScales.major(WesternChromatic.C4, 1);
 *
 * scale.forEach(note -> {
 *     double freq = tuning.getTone(note).asHertz();
 *     System.out.println(note + " = " + freq + " Hz");
 * });
 * }</pre>
 *
 * @param <T> the type of key position (typically {@link WesternChromatic})
 * @see WesternScales
 * @see StaticScale
 * @see SetIntervalScale
 */
public interface Scale<T extends KeyPosition> extends Plural<T> {
	int length();

	static <T extends KeyPosition> Scale<T> of(T... notes) {
		return new StaticScale<>(notes);
	}

	default void forEach(Consumer<T> consumer) {
		for (int i = 0; i < length(); i++) {
			consumer.accept(valueAt(i));
		}
	}
}
