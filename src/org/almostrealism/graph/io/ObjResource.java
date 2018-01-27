package org.almostrealism.graph.io;

import org.almostrealism.graph.MeshResource;
import org.almostrealism.io.IOStreams;
import org.almostrealism.io.Permissions;
import org.almostrealism.io.Resource;
import org.almostrealism.io.ResourceTranscoder;

import java.io.IOException;
import java.io.InputStream;

public class ObjResource implements Resource {


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

	public static class MeshTranscoder implements ResourceTranscoder<MeshResource, ObjResource> {
		@Override
		public ObjResource transcode(MeshResource r) {
			return null;
		}
	}
}
