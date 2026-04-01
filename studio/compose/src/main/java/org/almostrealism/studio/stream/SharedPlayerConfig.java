/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.stream;

/**
 * Configuration data transferred from a DAW client to the server when establishing
 * a shared-memory audio connection.
 */
public class SharedPlayerConfig {
	/** File system path for the shared memory region. */
	private String location;

	/** Unique stream key used to identify this connection. */
	private String stream;

	public String getLocation() { return location; }
	public void setLocation(String location) { this.location = location; }

	public String getStream() { return stream; }
	public void setStream(String stream) { this.stream = stream; }
}
