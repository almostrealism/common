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
 * Tests for the backend-independent compute-context coordination that can be exercised without
 * an accelerator.
 *
 * <p>These tests live in the {@code ctx} package (rather than the module's {@code hardware.test}
 * package) so they can reach the protected coordination steps of
 * {@link org.almostrealism.hardware.ctx.SubmissionStream}, which only a runner extending it
 * otherwise uses. They load no native library and need no device.</p>
 */
package org.almostrealism.hardware.ctx;
