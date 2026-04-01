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
 * JVM heap-based memory backend for hardware-accelerated compute operations.
 *
 * <p>This package provides a pure-JVM fallback memory provider that stores data
 * in ordinary Java arrays. It is used when no GPU or native memory backend is
 * available, enabling code paths that would normally require GPU memory to
 * function on systems without accelerator hardware.</p>
 */
package org.almostrealism.hardware.jvm;
