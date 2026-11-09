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
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shader;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.space.Scene;
import org.almostrealism.space.Triangle;

import java.io.IOException;

/**
 * A list of triangles in the RAW text format, in one of two layouts.
 *
 * <p>If the first data line holds two numbers, {@code points polygons}, it is followed by
 * that many {@code x y z} point lines and then that many {@code a b c} lines of 0-based point
 * indices. Otherwise every data line holds the nine coordinates of one triangle's three
 * corners, and a line that does not is skipped with a warning. Lines starting with {@code #}
 * are comments.</p>
 */
public class RawResource extends UnicodeResource {
	/**
	 * Reads a {@link RawResource} into a {@link SceneResource} of white, unshaded triangles.
	 */
	public static class SceneReader implements ResourceTranscoder<RawResource, SceneResource<Triangle>>, ConsoleFeatures {
		@Override
		public SceneResource<Triangle> transcode(RawResource r) throws IOException {
			Scene<Triangle> scene = new Scene<>();
			TextFormatReader in = new TextFormatReader("RAW", r.getInputStream());

			String line = in.nextOrNull();
			if (line == null) return new SceneResource<>(scene);

			if (line.trim().split("\\s+").length == 2) {
				double[] counts = in.values(line, 2);
				Vector[] points = new Vector[(int) counts[0]];

				for (int p = 0; p < points.length; p++) {
					double[] d = in.values(in.next(), 3);
					points[p] = new Vector(d[0], d[1], d[2]);
				}

				for (int p = 0; p < (int) counts[1]; p++) {
					double[] d = in.values(in.next(), 3);
					scene.add(triangle(points[(int) d[0]], points[(int) d[1]], points[(int) d[2]]));
				}
			} else {
				while (line != null) {
					try {
						double[] d = in.values(line, 9);
						scene.add(triangle(new Vector(d[0], d[1], d[2]),
								new Vector(d[3], d[4], d[5]),
								new Vector(d[6], d[7], d[8])));
					} catch (IOException e) {
						warn("Skipping a line that is not a triangle: " + e.getMessage());
					}

					line = in.nextOrNull();
				}
			}

			return new SceneResource<>(scene);
		}

		/**
		 * Returns a white, unshaded triangle with the given corners.
		 *
		 * @param a the first corner
		 * @param b the second corner
		 * @param c the third corner
		 * @return the triangle
		 */
		private static Triangle triangle(Vector a, Vector b, Vector c) {
			Triangle t = new Triangle(a, b, c);
			t.setColor(new RGB(1.0, 1.0, 1.0));
			t.setShaders(new Shader[0]);
			return t;
		}
	}
}
