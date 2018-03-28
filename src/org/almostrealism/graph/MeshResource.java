package org.almostrealism.graph;

import io.almostrealism.js.JsonResource;
import org.almostrealism.algebra.Vector;
import org.almostrealism.io.IOStreams;
import org.almostrealism.io.Permissions;
import org.almostrealism.io.Resource;
import org.almostrealism.io.ResourceTranscoder;

import java.io.IOException;
import java.io.InputStream;

public class MeshResource implements Resource<Mesh> {
	private Mesh mesh;
	private Permissions permissions;

	public MeshResource(Mesh m) {
		this.mesh = m;
		this.permissions = new Permissions();
	}

	@Override
	public void load(IOStreams io) throws IOException { }  // TODO  Read serialized data

	@Override
	public void load(byte data[], long offset, int len) { } // TODO  Read serialized data

	@Override
	public void loadFromURI() throws IOException { } // TODO

	@Override
	public void send(IOStreams io) throws IOException { } // TODO  Send serialized data

	@Override
	public void saveLocal(String file) throws IOException { }  // TODO Serialize the mesh

	@Override
	public String getURI() {
		return null;
	}  // TODO

	@Override
	public void setURI(String uri) { } // TODO

	@Override
	public Mesh getData() {
		return getMesh();
	}

	public Mesh getMesh() { return mesh; }

	@Override
	public InputStream getInputStream() {
		return null;
	} // TODO

	@Override
	public Permissions getPermissions() {
		return permissions;
	}

	public static class JsonTranscoder implements ResourceTranscoder<MeshResource, JsonResource> {
		@Override
		public JsonResource transcode(MeshResource r) {
			StringBuffer buf = new StringBuffer();

			buf.append("new Float32Array( [\n");

			Triangle t[] = r.getMesh().getTriangles();

			for (int i = 0; i < t.length; i++) {
				Vector v[] = t[i].getVertices();
				buf.append(v[0].getX());
				buf.append(", ");
				buf.append(v[0].getY());
				buf.append(", ");
				buf.append(v[0].getZ());
				buf.append(", \n");
				buf.append(v[1].getX());
				buf.append(", ");
				buf.append(v[1].getY());
				buf.append(", ");
				buf.append(v[1].getZ());
				buf.append(", \n");
				buf.append(v[2].getX());
				buf.append(", ");
				buf.append(v[2].getY());
				buf.append(", ");
				buf.append(v[2].getZ());

				if (i < (t.length - 1)) {
					// There are more coordinates to write
					buf.append(", \n");
				} else {
					// There are no more coordinates after this one
					buf.append("\n");
				}
			}

			buf.append("]);\n");

			return new JsonResource(buf.toString());
		}
	}
}
