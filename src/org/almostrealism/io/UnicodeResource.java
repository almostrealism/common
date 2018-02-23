package org.almostrealism.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;

public class UnicodeResource extends ResourceAdapter {
	private String data;
	
	public UnicodeResource() { }
	
	public UnicodeResource(String data) { this.data = data; }

	public synchronized void load(byte data[], int offset, int len) {
		this.data = new String(data, offset, len);
	}

	@Override
	public synchronized void load(IOStreams io) throws IOException { read(io.in); }

	@Override
	public synchronized void loadFromURI() throws IOException {
		read(new URL(getURI()).openStream());
	}
	
	private void read(InputStream in) throws IOException {
		StringBuffer buf = new StringBuffer();
		
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
			String line;
			
			while ((line = r.readLine()) != null) {
				buf.append(line);
				buf.append("\n");
			}
		}
		
		data = buf.toString();
	}

	@Override
	public Object getData() { return data.getBytes(); }
}
