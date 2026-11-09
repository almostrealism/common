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
import org.almostrealism.algebra.Vector;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.space.Mesh;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A mesh in the Wavefront OBJ text format.
 *
 * <p>Only geometry is read: {@code v x y z} vertex lines and {@code f} face lines, whose
 * entries are 1-based vertex indices optionally followed by {@code /texture/normal} indices,
 * which are ignored. Only the first three vertices of a face that is not a triangle are
 * used, and a face whose vertex index is not a number is skipped with a warning.</p>
 */
public class ObjResource extends UnicodeResource {
	/** A stream to read the data from in place of the loaded text, or {@code null}. */
	private InputStream inputStream;

	/**
	 * Creates an empty OBJ resource, to be loaded.
	 */
	public ObjResource() { }

	/**
	 * Creates an OBJ resource holding the contents of the given file.
	 *
	 * @param f the OBJ file
	 * @throws IOException if the file cannot be read
	 */
	public ObjResource(File f) throws IOException { super(f); }

	/**
	 * Creates an OBJ resource that is read from the given stream when it is transcoded,
	 * rather than being loaded first.
	 *
	 * @param in the stream of OBJ data
	 */
	public ObjResource(InputStream in) {
		super("");
		this.inputStream = in;
	}

	@Override
	public InputStream getInputStream() {
		if (inputStream != null) return inputStream;
		return super.getInputStream();
	}

	/**
	 * Reads an {@link ObjResource} into a {@link MeshResource}.
	 */
	public static class MeshReader implements ResourceTranscoder<ObjResource, MeshResource>, ConsoleFeatures {
		@Override
		public MeshResource transcode(ObjResource r) throws IOException {
			TextFormatReader in = new TextFormatReader("OBJ", r.getInputStream());

			List<Vector> vertices = new ArrayList<>();
			List<int[]> triangles = new ArrayList<>();
			boolean nonTriangular = false;

			String line;

			while ((line = in.nextOrNull()) != null) {
				if (line.startsWith("v ")) {
					double[] d = in.values(line.substring(2), 3);
					vertices.add(new Vector(d[0], d[1], d[2]));
				} else if (line.startsWith("f ")) {
					String[] entries = line.substring(2).trim().split("\\s+");
					if (entries.length < 3) throw in.error("a face needs at least three vertices");

					nonTriangular |= entries.length > 3;

					try {
						triangles.add(triangle(entries));
					} catch (NumberFormatException e) {
						warn(in.error("skipping a face whose vertex index is not a number").getMessage());
					}
				}
			}

			if (nonTriangular) {
				warn("Some faces are not triangles; only their first three vertices were used");
			}

			return new MeshResource(new Mesh(vertices.toArray(new Vector[0]), triangles.toArray(new int[0][])));
		}

		/**
		 * Returns the 0-based vertex indices of the first three entries of a face line.
		 *
		 * @param entries the face's entries, each a vertex index optionally followed by
		 *                {@code /texture/normal} indices
		 * @return the three vertex indices
		 * @throws NumberFormatException if a vertex index is not a number
		 */
		private static int[] triangle(String[] entries) {
			int[] triangle = new int[3];

			for (int i = 0; i < 3; i++) {
				triangle[i] = Integer.parseInt(entries[i].split("/")[0]) - 1;
			}

			return triangle;
		}
	}
}
