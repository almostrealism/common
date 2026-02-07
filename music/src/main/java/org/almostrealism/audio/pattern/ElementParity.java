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
 * Defines position adjustment for pattern elements relative to a base position.
 *
 * <p>{@code ElementParity} is used in {@link PatternElementFactory#apply} to
 * determine where an element is placed relative to the base position:</p>
 * <ul>
 *   <li><strong>NONE</strong>: Element is placed at the exact base position</li>
 *   <li><strong>LEFT</strong>: Element is placed before the base position (pos - scale)</li>
 *   <li><strong>RIGHT</strong>: Element is placed after the base position (pos + scale)</li>
 * </ul>
 *
 * <p>This enables creating symmetric or asymmetric patterns around beat positions.</p>
 *
 * @see PatternElementFactory#apply
 *
 * @author Michael Murray
 */
public enum ElementParity {
	NONE, LEFT, RIGHT
}
