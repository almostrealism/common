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

package org.almostrealism.space.format;

import io.almostrealism.resource.ResourceTranscoder;
import io.almostrealism.resource.UnicodeResource;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;

import java.io.File;
import java.io.IOException;

/**
 * A mesh in the ASCII form of the Polygon File Format (PLY).
 *
 * <p>The header's {@code element vertex} and {@code element face} lines give the counts, and
 * {@code end_header} ends it. Each vertex line starts with {@code x y z}; each face line is
 * {@code 3 a b c}, with vertex indices starting at 0. Other properties are ignored, and only
 * the first three vertices of a face that is not a triangle are used.</p>
 */
public class PlyResource extends UnicodeResource {
	/** Whether to log progress every {@link #PROGRESS_INTERVAL} vertices or faces. */
	public static boolean enableVerbose = false;

	/** How many vertices or faces are read between progress messages. */
	private static final int PROGRESS_INTERVAL = 100000;

	/**
	 * Creates an empty PLY resource, to be loaded.
	 */
	public PlyResource() { }

	/**
	 * Creates a PLY resource holding the contents of the given file.
	 *
	 * @param f the PLY file
	 * @throws IOException if the file cannot be read
	 */
	public PlyResource(File f) throws IOException { super(f); }

	/**
	 * Reads a {@link PlyResource} into a {@link MeshResource}.
	 */
	public static class MeshReader implements ResourceTranscoder<PlyResource, MeshResource>, ConsoleFeatures {
		@Override
		public MeshResource transcode(PlyResource r) throws IOException {
			TextFormatReader in = new TextFormatReader("PLY", r.getInputStream());

			int pointCount = 0;
			int triangleCount = 0;

			String line = in.next();

			while (!line.startsWith("end_header")) {
				if (line.startsWith("element vertex")) {
					pointCount = count(line);
				} else if (line.startsWith("element face")) {
					triangleCount = count(line);
				}

				line = in.next();
			}

			DefaultVertexData data = new DefaultVertexData(pointCount, triangleCount);

			for (int i = 0; i < pointCount; i++) {
				double[] d = in.values(in.next(), 3);
				data.getVertices().set(i, d[0], d[1], d[2]);
				progress(i + 1, pointCount, "vertices");
			}

			boolean nonTriangular = false;

			for (int i = 0; i < triangleCount; i++) {
				double[] d = in.values(in.next(), 4);
				nonTriangular |= (int) d[0] != 3;

				data.setTriangle(i, (int) d[1], (int) d[2], (int) d[3]);
				progress(i + 1, triangleCount, "faces");
			}

			if (nonTriangular) {
				warn("Some faces are not triangles; only their first three vertices were used");
			}

			log("Loaded " + pointCount + " vertices and " + triangleCount + " faces");
			return new MeshResource(new Mesh(data));
		}

		/**
		 * Returns the count at the end of an {@code element} header line.
		 *
		 * @param line the header line
		 * @return the count
		 */
		private static int count(String line) {
			return Integer.parseInt(line.substring(line.lastIndexOf(" ") + 1).trim());
		}

		/**
		 * Logs progress through a list, when {@link #enableVerbose} is set.
		 *
		 * @param done  how many items have been read
		 * @param total how many items there are
		 * @param items what the items are
		 */
		private void progress(int done, int total, String items) {
			if (enableVerbose && done % PROGRESS_INTERVAL == 0) {
				log(done + " of " + total + " " + items + " loaded");
			}
		}
	}
}
