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
 * GPU-accelerated audio signal computations using the Almost Realism Producer pattern.
 *
 * <p>This package contains computation implementations for audio signal generation and
 * processing, including envelope computations and filter kernels. All computations are
 * expressed as composable {@link io.almostrealism.relation.Producer} instances that
 * can be compiled to GPU kernels.</p>
 */
package org.almostrealism.audio.computations;
