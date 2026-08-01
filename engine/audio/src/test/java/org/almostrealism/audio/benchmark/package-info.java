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
 * Benchmarks for audio pattern rendering.
 *
 * <p>Contains performance measurement tests for the pattern rendering pipeline,
 * including the four-kernel chain (resample, volume envelope, FIR filter, accumulate)
 * and various batching strategies.</p>
 *
 * @see org.almostrealism.audio.BatchedPatternRenderer
 */
package org.almostrealism.audio.benchmark;