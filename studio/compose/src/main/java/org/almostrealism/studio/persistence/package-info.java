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
 * Persistence layer for audio library data in the Almost Realism studio compose module.
 * Classes in this package handle serialization and deserialization of audio library
 * metadata, wave details, and migration of legacy data formats.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.persistence.AudioLibraryPersistence} - Serializes
 *       and deserializes the audio library to/from Protocol Buffers</li>
 *   <li>{@link org.almostrealism.studio.persistence.AudioLibraryDataWriter} - Writes audio
 *       library data to persistent storage</li>
 *   <li>{@link org.almostrealism.studio.persistence.AudioLibraryMigration} - Handles
 *       migration of legacy audio library formats</li>
 *   <li>{@link org.almostrealism.studio.persistence.GeneratedSourceLibrary} - Library
 *       tracking generated audio source files</li>
 *   <li>{@link org.almostrealism.studio.persistence.LibraryDestination} - Destination
 *       configuration for audio library output</li>
 *   <li>{@link org.almostrealism.studio.persistence.MigrationClassLoader} - Class loader
 *       that handles migration of legacy serialized class names</li>
 *   <li>{@link org.almostrealism.studio.persistence.ProtobufWaveDetailsStore} - Protocol
 *       Buffers backed store for wave detail metadata</li>
 *   <li>{@link org.almostrealism.studio.persistence.WaveDetailsOutputLine} - Output line
 *       that writes wave details during audio rendering</li>
 * </ul>
 */
package org.almostrealism.studio.persistence;
