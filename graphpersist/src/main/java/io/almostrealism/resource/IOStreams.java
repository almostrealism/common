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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class IOStreams {
	public DataInputStream in;
	public DataOutputStream out;
	public String host;
	
	public IOStreams() { }

	public IOStreams(InputStream in) {
		this.in = in instanceof DataInputStream ? (DataInputStream) in : new DataInputStream(in);
		this.host = "localhost";
	}
	
	public IOStreams(Socket s) throws IOException {
		this.in = new DataInputStream(s.getInputStream());
		this.out = new DataOutputStream(s.getOutputStream());
		this.host = s.getInetAddress().toString();
	}
	
	public void close() throws IOException {
		this.in.close();
		this.out.close();
	}
}