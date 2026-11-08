/*
 * Copyright 2025 Michael Murray
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
 * Resource abstraction layer for loading, sending, and persisting arbitrary data.
 *
 * <p>A {@link io.almostrealism.resource.Resource} is a named, permissioned unit of data
 * that can be loaded from a URI, transferred over I/O streams, or saved locally. The core
 * types ({@link io.almostrealism.resource.Resource}, {@link io.almostrealism.resource.ResourceAdapter},
 * {@link io.almostrealism.resource.UnicodeResource}, {@link io.almostrealism.resource.IOStreams},
 * {@link io.almostrealism.resource.Permissions} and {@link io.almostrealism.resource.ResourceTranscoder})
 * live in {@code ar-io}, so they can be used without this module. This module adds
 * {@link io.almostrealism.resource.ResourceVariable}, which bridges resource data into the
 * expression scope system.</p>
 *
 * @see io.almostrealism.persist
 */
package io.almostrealism.resource;
