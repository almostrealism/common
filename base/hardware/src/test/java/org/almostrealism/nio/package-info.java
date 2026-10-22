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
 * Tests for {@link org.almostrealism.nio.NativeMemoryProvider} and the native buffers it manages
 * that can be exercised without a real accelerator.
 *
 * <p>This module sits below the engine layer, so tests here cannot extend {@code TestSuiteBase}
 * and are written as plain JUnit. A private direct-buffer provider needs no native compilation,
 * so the host-side read and write paths can be checked directly.</p>
 */
package org.almostrealism.nio;
