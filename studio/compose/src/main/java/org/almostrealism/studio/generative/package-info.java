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
 * Generative audio management infrastructure for the Almost Realism studio compose layer.
 * This package provides the resource management, provider, and generator abstractions
 * that support dynamic audio generation integrated with the pattern system.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.generative.GenerationManager} - Coordinates
 *       generation providers and pattern system integration</li>
 *   <li>{@link org.almostrealism.studio.generative.GenerationProvider} - Interface for
 *       supplying generated audio content to the scene</li>
 *   <li>{@link org.almostrealism.studio.generative.GenerationResourceManager} - Manages
 *       resources required for audio generation</li>
 *   <li>{@link org.almostrealism.studio.generative.Generator} - Core audio generator
 *       abstraction</li>
 *   <li>{@link org.almostrealism.studio.generative.GeneratorStatus} - Status tracking for
 *       ongoing generation operations</li>
 *   <li>{@link org.almostrealism.studio.generative.LocalResourceManager} - Local file-system
 *       backed resource manager</li>
 *   <li>{@link org.almostrealism.studio.generative.NoOpGenerationProvider} - No-operation
 *       provider used when generation is disabled</li>
 * </ul>
 */
package org.almostrealism.studio.generative;
