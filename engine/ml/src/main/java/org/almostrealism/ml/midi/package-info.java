/*
 * Copyright 2026 Michael Murray
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

/**
 * Moonbeam MIDI generation model — tokenization, embedding, transformer encoder,
 * GRU decoder, and autoregressive inference for compound MIDI token sequences.
 *
 * <p>Entry points:</p>
 * <ul>
 *   <li>{@link org.almostrealism.ml.midi.MoonbeamMidi} — full model (encoder + decoder)</li>
 *   <li>{@link org.almostrealism.ml.midi.MoonbeamMidiGenerator} — autoregressive generation loop</li>
 *   <li>{@link org.almostrealism.ml.midi.MidiTokenizer} — MIDI event ↔ compound token conversion</li>
 * </ul>
 */
package org.almostrealism.ml.midi;
