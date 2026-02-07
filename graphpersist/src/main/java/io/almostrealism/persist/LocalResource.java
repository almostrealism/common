/*
 * Copyright 2018 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.persist;

import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class LocalResource implements Resource {
	private String uri;
	private File file;
	private InputStream in;
	
	private final Permissions permissions;
	
	public LocalResource() {
		this.permissions = new Permissions();
	}
	
	public LocalResource(String uri) {
		this.setURI(uri);
		this.permissions = new Permissions();
	}
	
	public LocalResource(String uri, Permissions permissions) {
		this.setURI(uri);
		this.permissions = permissions;
	}
	
	public Object getData() { return this.file; }

	public InputStream getInputStream() {
		if (this.in != null) return in;
		if (this.file == null) return null;
		
		try {
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	public String getURI() { return this.file.getAbsolutePath(); }
	
	public Permissions getPermissions() { return permissions; }

	public void load(IOStreams io) throws IOException {
		this.in = io.in;
	}

	@Override
	public void load(byte[] bytes, long l, int i) {
		// TODO
	}

	public void loadFromURI() throws IOException {
		this.file = new File(this.uri);
	}

	public void saveLocal(String file) throws IOException {
		InputStream in = this.getInputStream();
		
		try (OutputStream out = new FileOutputStream(file)) {
			byte[] b = new byte[1];
			in.read(b);
			
			while (in.read(b) >= 0) { out.write(b); }
			
			out.flush();
		}
	}

	public void send(IOStreams io) throws IOException {
		InputStream in = this.getInputStream();
		
		byte[] b = new byte[1];
		in.read(b);
		
		while (in.read(b) >= 0) { io.out.write(b); }
		
		io.out.flush();
		io.out.close();
	}

	public void setURI(String uri) {
		this.uri = uri;
		this.file = new File(this.uri);
		this.in = null;
	}
}
