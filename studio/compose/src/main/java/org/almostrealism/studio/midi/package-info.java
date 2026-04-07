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
 * MIDI I/O and generation tooling for the studio composition layer.
 *
 * <p>This package provides file reading and writing ({@link org.almostrealism.music.midi.MidiFileReader}),
 * tokenization ({@link org.almostrealism.studio.midi.MidiTokenizer}),
 * dataset support ({@link org.almostrealism.studio.midi.MidiDataset}),
 * and autoregressive generation via
 * {@link org.almostrealism.studio.midi.MoonbeamMidiGenerator} and
 * {@link org.almostrealism.studio.midi.SkyTntMidi}.</p>
 *
 * <p>The canonical MIDI event type is
 * {@link org.almostrealism.music.midi.MidiNoteEvent} from the {@code ar-music} module.</p>
 */
package org.almostrealism.studio.midi;
