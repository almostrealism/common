package org.almostrealism.space;

import org.almostrealism.io.IOStreams;
import org.almostrealism.io.Permissions;
import org.almostrealism.io.Resource;

import java.io.IOException;
import java.io.InputStream;

public class SceneResource<T extends ShadableSurface> implements Resource<Scene<T>> {
	private Scene<T> scene;

	public SceneResource(Scene<T> s) { this.scene = s; }

	@Override
	public void load(byte data[], int offset, int len) { }

	@Override
	public void load(IOStreams io) throws IOException { }

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
	public Scene<T> getData() { return getScene(); }

	public Scene<T> getScene() { return scene; }

	@Override
	public InputStream getInputStream() {
		return null;
	}

	@Override
	public Permissions getPermissions() {
		return null;
	}
}
