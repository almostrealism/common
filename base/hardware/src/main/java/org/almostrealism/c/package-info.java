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
 * C language code generation infrastructure.
 *
 * <p>This package provides the C language operations and print writers used to produce compilable
 * kernel source code, along with {@link org.almostrealism.c.BaseNative}, the base class for
 * operations that compile and invoke C via JNI. These classes are used by both the JNI and OpenCL
 * backends. Native memory allocation itself lives in {@code org.almostrealism.nio}.</p>
 */
package org.almostrealism.c;
