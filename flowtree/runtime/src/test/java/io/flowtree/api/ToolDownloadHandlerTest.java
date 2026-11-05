/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link ToolDownloadHandler}: a registered tool's source is served
 * as plain text, and a missing registration or file yields a 404.
 */
public class ToolDownloadHandlerTest extends TestSuiteBase {

	/**
	 * Reads a response body fully as UTF-8.
	 *
	 * @param response the response to read
	 * @return the body text
	 * @throws Exception if the body cannot be read
	 */
	private String body(Response response) throws Exception {
		try (InputStream in = response.getData()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * A registered tool whose file exists is served with its exact content.
	 *
	 * @throws Exception if the temporary file cannot be written
	 */
	@Test(timeout = 30000)
	public void registeredToolIsServedVerbatim() throws Exception {
		Path file = Files.createTempFile("tool", ".py");
		file.toFile().deleteOnExit();
		String source = "def main():\n    print('ok')\n";
		Files.writeString(file, source, StandardCharsets.UTF_8);
		Map<String, Path> toolFiles = new HashMap<>();
		toolFiles.put("ar-example", file);

		Response response = new ToolDownloadHandler(toolFiles).handle("ar-example");

		Assert.assertEquals(Response.Status.OK, response.getStatus());
		Assert.assertEquals(source, body(response));
	}

	/**
	 * A tool registered after the handler was built is served, because the
	 * handler reads the shared live map rather than a copy.
	 *
	 * @throws Exception if the temporary file cannot be written
	 */
	@Test(timeout = 30000)
	public void toolRegisteredAfterConstructionIsServed() throws Exception {
		Map<String, Path> toolFiles = new HashMap<>();
		ToolDownloadHandler handler = new ToolDownloadHandler(toolFiles);
		Path file = Files.createTempFile("tool-late", ".py");
		file.toFile().deleteOnExit();
		Files.writeString(file, "x = 1\n", StandardCharsets.UTF_8);
		toolFiles.put("late", file);

		Response response = handler.handle("late");

		Assert.assertEquals(Response.Status.OK, response.getStatus());
		Assert.assertEquals("x = 1\n", body(response));
	}

	/**
	 * An unregistered name, and a registered name whose file has since been
	 * deleted, are both answered with a 404 naming the tool.
	 *
	 * @throws Exception if the temporary file cannot be created
	 */
	@Test(timeout = 30000)
	public void unknownOrDeletedToolIsNotFound() throws Exception {
		Path file = Files.createTempFile("tool-gone", ".py");
		Files.delete(file);
		Map<String, Path> toolFiles = new HashMap<>();
		toolFiles.put("gone", file);
		ToolDownloadHandler handler = new ToolDownloadHandler(toolFiles);

		Response unknown = handler.handle("never-registered");
		Assert.assertEquals(Response.Status.NOT_FOUND, unknown.getStatus());
		Assert.assertEquals("Tool not found: never-registered", body(unknown));

		Response deleted = handler.handle("gone");
		Assert.assertEquals(Response.Status.NOT_FOUND, deleted.getStatus());
		Assert.assertEquals("Tool not found: gone", body(deleted));
	}
}
