package org.almostrealism.graph.io;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.MeshResource;
import org.almostrealism.io.*;
import org.almostrealism.space.Scene;
import org.almostrealism.space.ShadableSurface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Stack;

public class GtsResource implements Resource {


	@Override
	public void load(IOStreams io) throws IOException {

	}

	@Override
	public void loadFromURI() throws IOException {

	}

	@Override
	public void send(IOStreams io) throws IOException {

	}

	@Override
	public void saveLocal(String file) throws IOException {

	}

	@Override
	public String getURI() {
		return null;
	}

	@Override
	public void setURI(String uri) {

	}

	@Override
	public Object getData() {
		return null;
	}

	@Override
	public InputStream getInputStream() {
		return null;
	}

	@Override
	public Permissions getPermissions() {
		return null;
	}

	public static class MeshTranscoder implements ResourceTranscoder<MeshResource, GtsResource> {
		@Override
		public GtsResource transcode(MeshResource r) {
			return null;
		}
	}

	public static class MeshReader implements ResourceTranscoder<GtsResource, MeshResource> {
		private Mesh s;

		public void setInitialMesh(Mesh m) { this.s = m; }

		@Override
		public MeshResource transcode(GtsResource r) throws IOException {
			Mesh m = new Mesh();
			if (s != null) m = s;

			BufferedReader in = new BufferedReader(new InputStreamReader(r.getInputStream()));

			int lineCount = 0;
			String line = null;

			int pointCount = 0, edgeCount = 0, triangleCount = 0;

			w: while (true) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;
				if (line.startsWith("#")) continue w;

				double d[] = FileDecoder.parseDoubles(line);

				pointCount = (int)d[0];
				edgeCount = (int)d[1];
				triangleCount = (int)d[2];

				break w;
			}

			int edges[][] = new int[edgeCount][2];

			i: for (int i = 0; i < pointCount; ) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;
				if (line.startsWith("#")) continue i;

				double d[] = FileDecoder.parseDoubles(line);
				m.addVector(new Vector(d[0], d[1], d[2]));

				i++;
			}

			i: for (int i = 0; i < edgeCount; ) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;
				if (line.startsWith("#")) continue i;

				double d[] = FileDecoder.parseDoubles(line);
				edges[i][0] = (int)d[0] - 1;
				edges[i][1] = (int)d[1] - 1;

				i++;
			}

			i: for (int i = 0; i < triangleCount; ) {
				line = in.readLine();
				lineCount++;
				if (line == null) return null;
				if (line.startsWith("#")) continue i;

				double d[] = FileDecoder.parseDoubles(line);

				Stack st = new Stack();

				for (int j = 0; j < d.length; j++) {
					int v[] = edges[(int)d[j] - 1];
					Integer v0 = Integer.valueOf(v[0]);
					Integer v1 = Integer.valueOf(v[1]);

					if (!st.contains(v0)) st.push(v0);
					if (!st.contains(v1)) st.push(v1);
				}

				m.addTriangle(((Integer)st.pop()).intValue(), ((Integer)st.pop()).intValue(), ((Integer)st.pop()).intValue());

				i++;
			}

			return new MeshResource(m);
		}
	}
}