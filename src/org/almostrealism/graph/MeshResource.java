package org.almostrealism.graph;

import org.almostrealism.io.IOStreams;
import org.almostrealism.io.Permissions;
import org.almostrealism.io.Resource;

import java.io.IOException;
import java.io.InputStream;

public class MeshResource implements Resource {
	private Mesh mesh;

	public MeshResource(Mesh m) {
		this.mesh = m;
	}

	@Override
	public void load(IOStreams io) throws IOException { }

	@Override
	public void load(byte data[], int offset, int len) { }

	@Override
	public void loadFromURI() throws IOException { }

	@Override
	public void send(IOStreams io) throws IOException { }

	@Override
	public void saveLocal(String file) throws IOException { }

	@Override
	public String getURI() {
		return null;
	}

	@Override
	public void setURI(String uri) { }

	@Override
	public Object getData() {
		return getMesh();
	}

	public Mesh getMesh() { return mesh; }

	@Override
	public InputStream getInputStream() {
		return null;
	}

	@Override
	public Permissions getPermissions() {
		return null;
	}
}
