package org.almostrealism.io;

import java.io.*;
import java.net.URL;

public class UnicodeResource extends ResourceAdapter<byte[]> {
	private String data;
	
	public UnicodeResource() { }
	
	public UnicodeResource(String data) { this.data = data; }

	public UnicodeResource(File f) throws IOException {
		read(new FileInputStream(f));
	}

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
	public byte[] getData() { return data.getBytes(); }
}
