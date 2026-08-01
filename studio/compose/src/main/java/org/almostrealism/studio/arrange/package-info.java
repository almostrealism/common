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
 * Arrangement and effects management subsystems for {@link org.almostrealism.studio.AudioScene}.
 * This package contains the manager classes that coordinate the various audio processing
 * stages during composition and rendering.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.arrange.AutomationManager} - Parameter automation
 *       envelope management over playback time</li>
 *   <li>{@link org.almostrealism.studio.arrange.EfxManager} - Per-channel effects chain
 *       routing and processing</li>
 *   <li>{@link org.almostrealism.studio.arrange.GlobalTimeManager} - Global clock resets and
 *       measure boundary tracking</li>
 *   <li>{@link org.almostrealism.studio.arrange.MixdownManager} - Delays, reverb, and final
 *       mix bus processing</li>
 *   <li>{@link org.almostrealism.studio.arrange.RiseManager} - Rise/swell audio effect
 *       processing</li>
 *   <li>{@link org.almostrealism.studio.arrange.SceneSectionManager} - Structural scene
 *       sections defining compositional regions</li>
 * </ul>
 */
package org.almostrealism.studio.arrange;
