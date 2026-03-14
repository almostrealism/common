/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.resource;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class ResourceAdapter<T extends Object> implements Resource<T> {
	private final Permissions permissions = new Permissions();
	
	protected String uri;
	
	@Override
	public void saveLocal(String file) throws IOException {
		try (FileOutputStream out = new FileOutputStream(file)) {
			byte[] data = (byte[]) getData();
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
		byte[] data = (byte[]) getData();
		if (data == null) return;
		
		for (int j = 0; j < data.length; j++)
			io.out.writeByte(data[j]);
	}

	@Override
	public Permissions getPermissions() { return permissions; }
}
