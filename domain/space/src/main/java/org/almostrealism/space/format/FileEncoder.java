/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.space.format;

import org.almostrealism.space.Scene;

import java.beans.XMLEncoder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Encodes {@link Scene}s for storage. Only the Java Beans XML encoding is supported; a mesh
 * can be written as GTS with {@link GtsResource.MeshTranscoder}.
 */
public class FileEncoder {
	/** The integer code for a Java Beans XML encoding, the same code {@link ModelData} reads. */
	public static final int XMLEncoding = ModelData.XMLEncoding;

	/**
	 * The integer code for a GTS encoding, the same code {@link ModelData} reads. Scenes are not
	 * written as GTS; it identifies a mesh to be written with {@link GtsResource.MeshTranscoder}.
	 */
	public static final int GTSEncoding = ModelData.GTSEncoding;

	/**
	 * Encodes the given scene with the given encoding into the given file, creating it if
	 * necessary. Nothing is written for an encoding that is not supported.
	 *
	 * @param scene    the scene to encode
	 * @param file     the file to write
	 * @param encoding the encoding code
	 * @throws IOException if the file cannot be created or written
	 */
	public static void encodeSceneFile(Scene scene, File file, int encoding) throws IOException {
		try (FileOutputStream fileOut = new FileOutputStream(file)) {
			encodeSceneFile(scene, fileOut, encoding);
		}
	}

	/**
	 * Encodes the given scene with the given encoding to the given stream. Nothing is written
	 * for an encoding that is not supported.
	 *
	 * @param scene    the scene to encode
	 * @param fileOut  the stream to write, which is closed when the scene has been written
	 * @param encoding the encoding code
	 * @throws IOException if writing fails
	 */
	public static void encodeSceneFile(Scene scene, OutputStream fileOut, int encoding) throws IOException {
		if (encoding == XMLEncoding) {
			try (XMLEncoder encoder = new XMLEncoder(fileOut)) {
				encoder.writeObject(scene);
			}
		}
	}
}
