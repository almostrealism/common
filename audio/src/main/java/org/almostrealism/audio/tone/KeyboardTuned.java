/*
 * Copyright 2024 Michael Murray
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

/**
 * Interface for components that can be configured with a keyboard tuning system.
 *
 * <p>KeyboardTuned allows audio components to receive tuning information
 * from a {@link KeyboardTuning} instance, enabling support for different
 * tuning systems (equal temperament, just intonation, etc.).</p>
 *
 * @see KeyboardTuning
 * @see DefaultKeyboardTuning
 */
public interface KeyboardTuned {
	void setTuning(KeyboardTuning tuning);
}
