/*
 * Copyright 2025 Michael Murray
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
 * Value sequence cells and computations for time-stepped parameter automation.
 *
 * <p>This package provides {@link org.almostrealism.audio.sequence.ValueSequenceCell}
 * for stepping through discrete parameter values over time, and supporting push/tick
 * computations. Value sequences are used in the grid routing ({@code CellFeatures.grid()})
 * to cycle through audio cell choices at fixed time intervals.</p>
 */
package org.almostrealism.audio.sequence;
