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

package org.almostrealism.audio.arrange;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.filter.AudioProcessor;

/**
 * Represents a timed section within a channel that can process audio.
 *
 * <p>{@code ChannelSection} defines a region within the arrangement where
 * specific audio processing applies. Each section has:</p>
 * <ul>
 *   <li>{@link #getPosition()}: Start position in measures</li>
 *   <li>{@link #getLength()}: Duration in measures</li>
 *   <li>Audio processing via inherited {@link AudioProcessor#process}</li>
 * </ul>
 *
 * <h2>Usage in Pattern System</h2>
 *
 * <p>Sections are stored in {@link AudioSceneContext#getSections()} and used by:</p>
 * <ul>
 *   <li>{@link PatternLayerManager#sum}: To determine section activity and apply
 *       section-specific processing to pattern audio</li>
 *   <li>{@link AudioSceneContext#getSection(double)}: To find the active section
 *       at any given measure position</li>
 * </ul>
 *
 * <h2>Section Processing</h2>
 *
 * <p>The inherited {@link AudioProcessor#process} method is called after pattern
 * audio is rendered to the destination buffer. This enables section-specific
 * effects like fades, filters, or other processing.</p>
 *
 * <h2>Real-Time Considerations</h2>
 *
 * <p>Section processing currently happens on the full pattern buffer. For real-time
 * rendering, the process method would need to work on incremental buffers and
 * maintain state across buffer boundaries.</p>
 *
 * @see AudioSceneContext
 * @see PatternLayerManager#sum
 * @see AudioProcessor
 *
 * @author Michael Murray
 */
public interface ChannelSection extends AudioProcessor, Destroyable {
	/** Position of the section, in measures. */
	int getPosition();

	/** Length of the section, in measures. */
	int getLength();
}
