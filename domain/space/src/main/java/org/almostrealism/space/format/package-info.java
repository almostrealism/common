/*
 * Copyright 2026 Michael Murray
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
 * File formats for {@link org.almostrealism.space.Mesh} and {@link org.almostrealism.space.Scene}
 * data.
 *
 * <p>Each text format is a {@link io.almostrealism.resource.UnicodeResource} with a nested
 * {@link io.almostrealism.resource.ResourceTranscoder} that reads it into a
 * {@link org.almostrealism.space.format.MeshResource} or
 * {@link org.almostrealism.space.format.SceneResource}:</p>
 * <ul>
 *   <li>{@link org.almostrealism.space.format.GtsResource} — GNU Triangulated Surface, read and written</li>
 *   <li>{@link org.almostrealism.space.format.PlyResource} — ASCII PLY, read</li>
 *   <li>{@link org.almostrealism.space.format.ObjResource} — Wavefront OBJ, read</li>
 *   <li>{@link org.almostrealism.space.format.RawResource} — RAW triangle lists, read</li>
 * </ul>
 *
 * <p>{@link org.almostrealism.space.format.ModelData} and
 * {@link org.almostrealism.space.format.FileDecoder} choose a reader by integer encoding code
 * (including Java Beans XML for whole scenes), {@link org.almostrealism.space.format.FileEncoder}
 * writes scenes as XML, {@link org.almostrealism.space.format.FileSystemSceneLoader} loads an XML
 * scene from a path, and {@link org.almostrealism.space.format.MeshFile} is a surface that loads its
 * mesh from a file or URL the first time it is used.</p>
 */
package org.almostrealism.space.format;
