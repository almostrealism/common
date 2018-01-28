package org.almostrealism.io;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class ResourceAdapter implements Resource {
	private Permissions permissions = new Permissions();
	
	protected String uri;
	
	@Override
	public void saveLocal(String file) throws IOException {
		try (FileOutputStream out = new FileOutputStream(file)) {
			byte data[] = (byte[]) getData();
			if (data == null) return;
			
			for (int j = 0; j < data.length; j++)
				out.write(data[j]);
		}
	}
	
	@Override
	public String getURI() { return uri; }
	
	@Override
	public void setURI(String uri) { this.uri = uri; }
	
	@Override
	public InputStream getInputStream() {
		return new ByteArrayInputStream((byte[]) getData());
	}
	
	// TODO  This could be made faster by writing a range of bytes at a time
	public synchronized void send(IOStreams io) throws IOException {
		byte data[] = (byte[]) getData();
		if (data == null) return;
		
		for (int j = 0; j < data.length; j++)
			io.out.writeByte(data[j]);
	}

	@Override
	public Permissions getPermissions() { return permissions; }
}
