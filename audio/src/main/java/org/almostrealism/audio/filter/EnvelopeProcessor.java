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

package org.almostrealism.audio.filter;

import org.almostrealism.collect.PackedCollection;

// TODO  Should use AudioProcessor instead
@Deprecated
public interface EnvelopeProcessor {
	void setDuration(double duration);

	void setAttack(double attack);

	void setDecay(double decay);

	void setSustain(double sustain);

	void setRelease(double release);

	void process(PackedCollection input, PackedCollection output);
}
