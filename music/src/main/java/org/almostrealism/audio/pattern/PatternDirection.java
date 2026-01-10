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

package org.almostrealism.audio.pattern;

/**
 * Defines the playback direction for pattern elements.
 *
 * <p>{@code PatternDirection} controls the temporal direction in which
 * pattern elements are played:</p>
 * <ul>
 *   <li><strong>FORWARD</strong>: Elements play from start to end (normal)</li>
 *   <li><strong>BACKWARD</strong>: Elements play in reverse order</li>
 * </ul>
 *
 * <p>This is primarily used for creative effects like reverse percussion
 * fills or reversed melodic phrases.</p>
 *
 * @see PatternElement#getDirection
 *
 * @author Michael Murray
 */
public enum PatternDirection {
	FORWARD, BACKWARD
}
