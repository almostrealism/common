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
 * that can be loaded from a URI, transferred over I/O streams, or saved locally. Key types:</p>
 * <ul>
 *   <li>{@link io.almostrealism.resource.Resource} — Core interface defining load/send/save operations</li>
 *   <li>{@link io.almostrealism.resource.ResourceAdapter} — Skeletal implementation for byte-array resources</li>
 *   <li>{@link io.almostrealism.resource.UnicodeResource} — Text-based resource loaded from files, streams, or URLs</li>
 *   <li>{@link io.almostrealism.resource.IOStreams} — Paired input/output stream container for socket-based transfer</li>
 *   <li>{@link io.almostrealism.resource.Permissions} — POSIX-style permission model (owner/group/others)</li>
 *   <li>{@link io.almostrealism.resource.ResourceTranscoder} — Converts between resource types</li>
 *   <li>{@link io.almostrealism.resource.ResourceVariable} — Bridges resource data into the expression scope system</li>
 * </ul>
 *
 * @see io.almostrealism.nfs
 * @see io.almostrealism.persist
 */
package io.almostrealism.resource;
