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

package org.almostrealism.space.format;

import org.almostrealism.color.ShadableSurface;
import org.almostrealism.space.Scene;
import org.almostrealism.space.SurfaceGroup;

import java.beans.ExceptionListener;
import java.beans.XMLDecoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Decodes a single surface from a file, in addition to the whole scenes {@link ModelData}
 * decodes from a stream.
 */
public class FileDecoder extends ModelData {
	/**
	 * Decodes the surface stored in the given file using the given encoding.
	 *
	 * <p>An XML file holds the surface itself; a RAW file's triangles are returned as one
	 * {@link SurfaceGroup}; a mesh format's mesh is returned as it is.</p>
	 *
	 * @param file     the file to read
	 * @param encoding the encoding code of the data
	 * @param listener notified of exceptions while decoding XML; may be {@code null}
	 * @param s        for GTS, a mesh to add the data to in place of a new one; may be {@code null}
	 * @return the surface, or {@code null} if the encoding is not supported
	 * @throws IOException if the file cannot be read or decoded
	 */
	public static ShadableSurface decodeSurfaceFile(File file, int encoding,
													ExceptionListener listener, ShadableSurface s) throws IOException {
		try (FileInputStream fileIn = new FileInputStream(file)) {
			if (encoding == XMLEncoding) {
				XMLDecoder decoder = new XMLDecoder(fileIn);
				decoder.setExceptionListener(listener);
				return (ShadableSurface) decoder.readObject();
			} else if (encoding == RAWEncoding) {
				Scene<?> scene = decodeScene(fileIn, RAWEncoding, listener, s);
				return new SurfaceGroup<>(scene.getSurfaces());
			} else if (encoding == GTSEncoding || encoding == PLYEncoding || encoding == OBJEncoding) {
				Scene<?> scene = decodeScene(fileIn, encoding, listener, s);
				return scene.get(0);
			} else {
				return null;
			}
		}
	}
}
