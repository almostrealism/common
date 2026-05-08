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

/**
 * Audio-domain PDSL primitives. Registers audio DSP capabilities (FIR/biquad/delay,
 * LFO, multi-channel routing, fan-out, delay networks) with the
 * {@link org.almostrealism.ml.dsl.PdslInterpreter} from the audio module so the PDSL
 * core in {@code engine/ml} stays free of audio-specific dispatch.
 */
package org.almostrealism.studio.dsl.audio;
