/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.nfs.test;

import io.almostrealism.nfs.DeletionNotifier;
import io.almostrealism.nfs.DirectoryNotifier;
import io.almostrealism.nfs.GraphFileSystem;
import io.almostrealism.nfs.SearchEngine;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Graph;
import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import org.almostrealism.util.TestSuiteBase;
import org.dcache.nfs.vfs.FileHandle;
import org.dcache.nfs.vfs.Inode;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link GraphFileSystem#move(Inode, String, Inode, String)}, verifying
 * that the copy-and-delete implementation correctly transfers content, URI,
 * and permissions from source to destination.
 */
public class GraphFileSystemMoveTest extends TestSuiteBase {

	/**
	 * Verifies that move transfers the URI and permissions to the destination
	 * and deletes the source.
	 */
	@Test(timeout = 5000)
	public void moveTransfersUriAndPermissions() throws IOException {
		StubResource source = new StubResource("/src/file.txt");
		source.getPermissions().update(new Permissions("owner",
				Permissions.Setting.READ_WRITE_EXECUTE,
				Permissions.Setting.READ_EXECUTE,
				Permissions.Setting.READ));

		Map<String, StubResource> store = new HashMap<>();
		List<String> deletedPaths = new ArrayList<>();
		List<StubResource> created = new ArrayList<>();

		SearchEngine search = path -> {
			StubResource r = store.get(path);
			return r != null ? Collections.singletonList(r) : Collections.emptyList();
		};
		DeletionNotifier del = path -> { deletedPaths.add(path); return true; };
		DirectoryNotifier dir = path -> true;
		Factory<StubResource> factory = () -> {
			StubResource r = new StubResource("");
			created.add(r);
			return r;
		};

		store.put("/src/file.txt", source);

		GraphFileSystem<StubResource> fs = new GraphFileSystem<>(null, factory, search, dir, del);

		Inode srcInode = new Inode(new FileHandle.FileHandleBuilder().build("/src".getBytes()));
		Inode destInode = new Inode(new FileHandle.FileHandleBuilder().build("/dest".getBytes()));

		boolean result = fs.move(srcInode, "file.txt", destInode, "renamed.txt");

		Assert.assertTrue("Move should return true", result);
		Assert.assertEquals("Source should be deleted", 1, deletedPaths.size());
		Assert.assertEquals("/src/file.txt", deletedPaths.get(0));
		Assert.assertEquals("One new resource created", 1, created.size());
		Assert.assertEquals("/dest/renamed.txt", created.get(0).getURI());
	}

	/**
	 * Verifies that move returns false when the source does not exist.
	 */
	@Test(timeout = 5000)
	public void moveReturnsFalseWhenSourceMissing() throws IOException {
		List<String> deletedPaths = new ArrayList<>();

		SearchEngine search = path -> Collections.emptyList();
		DeletionNotifier del = path -> { deletedPaths.add(path); return true; };
		DirectoryNotifier dir = path -> true;
		Factory<StubResource> factory = () -> new StubResource("");

		GraphFileSystem<StubResource> fs = new GraphFileSystem<>(null, factory, search, dir, del);

		Inode srcInode = new Inode(new FileHandle.FileHandleBuilder().build("/src".getBytes()));
		Inode destInode = new Inode(new FileHandle.FileHandleBuilder().build("/dest".getBytes()));

		boolean result = fs.move(srcInode, "missing.txt", destInode, "dest.txt");

		Assert.assertFalse("Move should return false for missing source", result);
		Assert.assertTrue("Nothing should be deleted", deletedPaths.isEmpty());
	}

	/**
	 * Verifies that move transfers content data from source to destination.
	 */
	@Test(timeout = 5000)
	public void moveTransfersContent() throws IOException {
		byte[] content = "Hello, World!".getBytes();
		StubResource source = new StubResource("/src/data.bin");
		source.setContent(content);

		Map<String, StubResource> store = new HashMap<>();
		List<StubResource> created = new ArrayList<>();

		SearchEngine search = path -> {
			StubResource r = store.get(path);
			return r != null ? Collections.singletonList(r) : Collections.emptyList();
		};
		DeletionNotifier del = path -> true;
		DirectoryNotifier dir = path -> true;
		Factory<StubResource> factory = () -> {
			StubResource r = new StubResource("");
			created.add(r);
			return r;
		};

		store.put("/src/data.bin", source);

		GraphFileSystem<StubResource> fs = new GraphFileSystem<>(null, factory, search, dir, del);

		Inode srcInode = new Inode(new FileHandle.FileHandleBuilder().build("/src".getBytes()));
		Inode destInode = new Inode(new FileHandle.FileHandleBuilder().build("/dest".getBytes()));

		fs.move(srcInode, "data.bin", destInode, "copy.bin");

		Assert.assertEquals("Destination should have content", 1, created.size());
		Assert.assertNotNull("Content should be transferred", created.get(0).loadedData);
		Assert.assertArrayEquals("Content should match", content, created.get(0).loadedData);
	}

	/**
	 * Minimal Resource stub for testing move operations.
	 */
	private static class StubResource implements Resource<byte[]> {
		private String uri;
		private Permissions permissions;
		private byte[] content;
		byte[] loadedData;

		StubResource(String uri) {
			this.uri = uri;
			this.permissions = new Permissions();
		}

		void setContent(byte[] content) {
			this.content = content;
		}

		@Override public void load(IOStreams io) { }
		@Override public void load(byte[] data, long offset, int len) {
			this.loadedData = new byte[len];
			System.arraycopy(data, (int) offset, this.loadedData, 0, len);
		}
		@Override public void loadFromURI() { }
		@Override public void send(IOStreams io) { }
		@Override public void saveLocal(String file) { }
		@Override public String getURI() { return uri; }
		@Override public void setURI(String uri) { this.uri = uri; }
		@Override public byte[] getData() { return content; }
		@Override public InputStream getInputStream() {
			return content != null ? new ByteArrayInputStream(content) : null;
		}
		@Override public Permissions getPermissions() { return permissions; }
	}
}
