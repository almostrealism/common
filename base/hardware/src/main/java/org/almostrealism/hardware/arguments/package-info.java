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
 * Kernel argument resolution and process tree position-based argument mapping.
 *
 * <p>This package provides the infrastructure for mapping kernel arguments to their
 * positions in a {@link io.almostrealism.compute.Process} tree, enabling dynamic
 * argument substitution and reuse of compiled instruction sets across different
 * input producers. Key class: {@link org.almostrealism.hardware.arguments.ProcessArgumentMap}.</p>
 */
package org.almostrealism.hardware.arguments;
