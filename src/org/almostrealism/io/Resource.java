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

package org.almostrealism.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author  Michael Murray
 */
public interface Resource {
	void load(IOStreams io) throws IOException;
	void load(byte data[], int offset, int len);
	void loadFromURI() throws IOException;
	
	void send(IOStreams io) throws IOException;
	
	void saveLocal(String file) throws IOException;
	
	String getURI();
	void setURI(String uri);
	
	Object getData();
	InputStream getInputStream();

	Permissions getPermissions();
}
