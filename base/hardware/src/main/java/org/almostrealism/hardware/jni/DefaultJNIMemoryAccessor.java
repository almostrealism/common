/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.jni;

/**
 * Default {@link JNIMemoryAccessor} for standard heap memory access via JNI.
 *
 * <p>Uses the default {@link JNIMemoryAccessor#copyInline} implementation which generates
 * straightforward pointer casts from {@code argArr}.</p>
 *
 * <h2>Generated Code</h2>
 *
 * <pre>
 * double *arg0 = ((double *) argArr[0]);
 * float *arg1 = ((float *) argArr[1]);
 * </pre>
 *
 * @see JNIMemoryAccessor
 * @see CJNIPrintWriter
 */
public class DefaultJNIMemoryAccessor implements JNIMemoryAccessor {
}
