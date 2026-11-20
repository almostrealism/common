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
 * Tests for package-private parts of the Metal backend that need no Metal device, such as
 * {@link org.almostrealism.hardware.metal.QuietCommitDeadline}. They live in the backend's own
 * package so they can reach those parts directly. This module sits below the engine layer, so
 * they are plain JUnit rather than {@code TestSuiteBase} tests; tests that need a live Metal
 * device belong in a module above this one.
 */
package org.almostrealism.hardware.metal;
