/*
 * Copyright 2021 Michael Murray
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
 * Enumeration of key numbering schemes for musical keyboards.
 *
 * <p>KeyNumbering defines different conventions for numbering piano keys,
 * particularly relevant when mapping between different musical notation systems
 * and hardware interfaces.</p>
 *
 * <h2>Numbering Schemes</h2>
 * <ul>
 *   <li><b>STANDARD</b>: Traditional piano key numbering (A0 = 1, C4 = 40, etc.)</li>
 *   <li><b>MIDI</b>: MIDI protocol numbering (A0 = 21, C4 = 60, A4 = 69, etc.)</li>
 * </ul>
 *
 * <p>The MIDI numbering scheme is commonly used in digital audio workstations and
 * electronic instruments, where middle C (C4) is assigned MIDI note number 60.</p>
 *
 * @see KeyPosition
 * @see KeyboardTuning
 */
public enum KeyNumbering {
	STANDARD, MIDI
}
