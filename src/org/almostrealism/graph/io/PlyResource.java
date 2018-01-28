package org.almostrealism.graph.io;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.MeshResource;
import org.almostrealism.io.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class PlyResource extends UnicodeResource {
	public static class MeshTranscoder implements ResourceTranscoder<MeshResource, PlyResource> {
		@Override
		public PlyResource transcode(MeshResource r) {
			return null;
		}
	}

	public static class MeshReader implements ResourceTranscoder<PlyResource, MeshResource> {
		private Mesh s;

		public void setInitialMesh(Mesh m) { this.s = m; }

		@Override
		public MeshResource transcode(PlyResource r) throws IOException {
			Mesh m = new Mesh();
			if (s != null) m = s;

			BufferedReader in = new BufferedReader(new InputStreamReader(r.getInputStream()));

			int lineCount = 0;
			String line;

			int pointCount = 0, triangleCount = 0;

			w: while (true) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;

				if (line.startsWith("element")) {
					if (line.indexOf("vertex") > 0)
						pointCount = Integer.parseInt(line.substring(line.lastIndexOf(" ") + 1));
					if (line.indexOf("face") > 0)
						triangleCount = Integer.parseInt(line.substring(line.lastIndexOf(" ") + 1));
				} else if (line.startsWith("end_header")) {
					break w;
				}
			}

			i: for (int i = 0; i < pointCount; ) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;
				if (line.startsWith("#")) continue i;

				double d[] = FileDecoder.parseDoubles(line);
				m.addVector(new Vector(d[0], d[1], d[2]));

				i++;
			}

			i: for (int i = 0; i < triangleCount; ) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;
				if (line.startsWith("#")) continue i;

				double d[] = FileDecoder.parseDoubles(line);

				m.addTriangle((int) d[1], (int) d[2], (int) d[3]);

				i++;
			}

			return new MeshResource(m);
		}
	}
}
