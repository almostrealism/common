/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.graph.io;

import java.beans.XMLEncoder;
import java.io.*;

import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.MeshResource;
import org.almostrealism.graph.Triangulatable;
import org.almostrealism.space.Scene;
import org.almostrealism.space.ShadableSurface;

// TODO  Add GIF encoding.
// TODO  Add GTS and RAW encoding.

/**
 * The FileEncoder class provides static methods for encoding Scene and Surface objects
 * and storing them in local files.
 */
public class FileEncoder {

  /** The integer code for an XML encoding. */
  public static final int XMLEncoding = 2;
  
  /** The integer code for a GTS encoding. */
  public static final int GTSEncoding = 3;
  
  /**
	 * Encodes the specified Scene object using the encoding specified by the integer encoding code
	 * and saves the encoded data in the file represented by the specified File object.
	 * If the encoding code is not recognized, the method returns.
	 */
	public static void encodeSceneFile(Scene scene, File file, int encoding) throws IOException {
		if (file.exists() != true) {
			if (!file.createNewFile()) {
				System.out.println("FileEncoder: Unable to create " + file);
				return;
			}
		}
		
		FileOutputStream fileOut = new FileOutputStream(file);
		
		if (encoding == FileEncoder.XMLEncoding) {
			try (XMLEncoder encoder = new XMLEncoder(fileOut)) {
				encoder.writeObject(scene);
			}
		}
	}
	
	/**
	 * Encodes the specified Surface object using the encoding specified by the integer encoding code
	 * and saves the encoded data in the file represented by the specified File object.
	 * If the encoding code is not recognized, the method returns.
	 */
	public static void encodeSurfaceFile(ShadableSurface surface, File file, int encoding) throws IOException {
		if (file.exists() != true) {
			file.createNewFile();
		}
		
		FileOutputStream fileOut = new FileOutputStream(file);
		
		if (encoding == FileEncoder.XMLEncoding) {
			try (XMLEncoder encoder = new XMLEncoder(fileOut)) {
				encoder.writeObject(surface);
			}
		} else if (encoding == FileEncoder.GTSEncoding) {
			Mesh m;

			if (surface instanceof Mesh) {
				m = (Mesh) surface;
			} else if (surface instanceof Triangulatable) {
				m = ((Triangulatable) surface).triangulate();
			} else {
				return;
			}

			InputStream in = new GtsResource.MeshTranscoder().transcode(new MeshResource(m)).getInputStream();
			BufferedReader r = new BufferedReader(new InputStreamReader(in));

			try (PrintStream p = new PrintStream(fileOut)) {
				String line;

				while ((line = r.readLine()) != null) {
					p.println(line);
				}

				p.flush();
			}
		}
	}
}
