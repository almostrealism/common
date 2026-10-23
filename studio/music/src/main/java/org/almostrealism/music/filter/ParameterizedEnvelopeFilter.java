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

package org.almostrealism.music.filter;

import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;

/**
 * A {@link NoteAudioFilter} created by a {@link ParameterizedEnvelope}.
 *
 * <p>The filter is fully determined by the envelope that created it together with
 * the {@link ParameterSet} and {@link ChannelInfo.Voicing} it was created with, so
 * passing those back to {@link ParameterizedEnvelope#createFilter} produces an
 * equal filter. This is what allows a pattern containing envelope-shaped notes to
 * be persisted and restored exactly.</p>
 *
 * @see ParameterizedEnvelope
 */
public interface ParameterizedEnvelopeFilter extends NoteAudioFilter {

	/** Returns the envelope that created this filter. */
	ParameterizedEnvelope getEnvelope();

	/** Returns the parameters this filter was created with. */
	ParameterSet getParameters();

	/** Returns the voicing this filter was created for. */
	ChannelInfo.Voicing getVoicing();
}
