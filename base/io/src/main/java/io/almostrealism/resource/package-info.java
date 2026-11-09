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
 * The core resource abstraction: named, permissioned units of data that can be loaded from
 * streams, bytes or a URI, sent over streams, or saved locally. It lives here, rather than
 * with the persistence layer, so that code which reads or writes resources does not depend
 * on it. Key types:
 * <ul>
 *   <li>{@link io.almostrealism.resource.Resource} — Core interface defining load/send/save operations</li>
 *   <li>{@link io.almostrealism.resource.ResourceAdapter} — Skeletal implementation for byte-array resources</li>
 *   <li>{@link io.almostrealism.resource.UnicodeResource} — Text-based resource loaded from files, streams, or URLs</li>
 *   <li>{@link io.almostrealism.resource.IOStreams} — Paired input/output streams; a load or send may be a
 *       request/response exchange that uses both</li>
 *   <li>{@link io.almostrealism.resource.Permissions} — POSIX-style permission model (owner/group/others)</li>
 *   <li>{@link io.almostrealism.resource.ResourceTranscoder} — Converts one resource type to another,
 *       such as a file format into the data it describes</li>
 * </ul>
 *
 * <p>{@code ar-graphpersist} adds {@code ResourceVariable} to this package.</p>
 */
package io.almostrealism.resource;
