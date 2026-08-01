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

package io.almostrealism.nfs;

import java.io.InputStream;

import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Graph;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import org.almostrealism.io.ConsoleFeatures;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.DirectoryStream;
import org.dcache.nfs.vfs.FileHandle;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.Stat.Type;
import org.dcache.nfs.vfs.VirtualFileSystem;

import javax.security.auth.Subject;
import java.io.IOException;
import java.util.Iterator;

/**
 * An NFS4 {@link VirtualFileSystem} implementation backed by a {@link Graph} of
 * {@link Resource} objects.
 *
 * <p>This file system uses a {@link SearchEngine} to resolve paths to resources,
 * a {@link Factory} to create new resources, a {@link DirectoryNotifier} to handle
 * directory creation, and a {@link DeletionNotifier} to handle deletions.
 * Permissions are mapped bidirectionally between Unix-style octal mode bits and
 * the {@link io.almostrealism.resource.Permissions} model.</p>
 *
 * @param <T> The type of {@link Resource} created by this file system's factory
 */
public class GraphFileSystem<T extends Resource> implements VirtualFileSystem, ConsoleFeatures {
	/** One kilobyte, in bytes. */
	public static final long KB = 1024;
	/** One megabyte, in bytes. */
	public static final long MB = 1024 * KB;
	/** One gigabyte, in bytes. */
	public static final long GB = 1024 * MB;
	/** Total reported file system space: 1 petabyte. */
	public static final long TOTAL_SPACE = 1024L * 1024L * GB; // 1 petabtyte
	/** Total reported file count: 6 GB of address space assuming file names are at most 2 KB. */
	public static final long TOTAL_FILES = 6L * GB / (2L * KB); // 6 GB of FS address space assuming
																    // file names are at most 2KB

	/** The resource graph used to track nodes and edges. */
	private final Graph<Resource> graph;
	/** Factory used to construct new resource instances. */
	private final Factory<T> factory;
	/** Search engine for resolving paths to resources. */
	private final SearchEngine search;
	/** Notifier called when a new directory is created. */
	private final DirectoryNotifier dir;
	/** Notifier called when a resource is deleted. */
	private final DeletionNotifier del;

	/**
	 * Constructs a {@link GraphFileSystem} backed by the supplied graph and services.
	 *
	 * @param graph   The resource graph for node tracking
	 * @param factory Factory for creating new resources
	 * @param e       Search engine for resolving paths to resources
	 * @param dir     Notifier for directory creation events
	 * @param del     Notifier for deletion events
	 */
	public GraphFileSystem(Graph<Resource> graph, Factory<T> factory, SearchEngine e,
						   DirectoryNotifier dir, DeletionNotifier del) {
		this.graph = graph;
		this.factory = factory;
		this.search = e;
		this.dir = dir;
		this.del = del;
	}

	@Override
	public int access(Subject subject, Inode inode, int mode) throws IOException {
		log("GraphFileSystem: Access " + inode);
		return getModeForPermissions(((ResourceInode) inode).getPermissions()) & mode;
	}

	@Override
	public Inode create(Inode parent, Type type, String path, Subject subject, int mode) throws IOException {
		if (type == Type.DIRECTORY) {
			// TODO  Check if this user has permission to create this Inode
			dir.newDirectory(path);
			return new Inode(new FileHandle.FileHandleBuilder().build(path.getBytes()));
		} else if (type == Type.REGULAR) {
			// TODO  Check if this user has permission to create this Inode
			T r = factory.construct();
			r.setURI(path);

			// TODO  Check if this user has permission to set permissions in this way
			Permissions p = getPermissionsForMode(mode);
			p = new Permissions(subject.getPrincipals().iterator().next().getName(),
						p.getUserSetting(), p.getGroupSetting(), p.getOthersSetting());
			r.getPermissions().update(p);
			return new ResourceInode(r);
		} else {
			// TODO  Add support for other types
			// TODO  A symlink should add an edge to the resource graph
			// TODO  A unix socket should create a message queue
			// TODO  A block device should create an SQL blob or some such thing
			// TODO  A character device should create a neural network or something
			// TODO  A named pipe should create a flume channel
			throw new IOException(type + " is not yet supported by GraphPersist");
		}
	}

	@Override
	public FsStat getFsStat() throws IOException {
		return new FsStat(TOTAL_SPACE, TOTAL_FILES, 0, graph.countNodes());
	}

	@Override
	public Inode getRootInode() throws IOException {
		log("GraphFileSystem: getRootInode()");
		return new Inode(new FileHandle("/".getBytes()));
	}

	@Override
	public Inode lookup(Inode parent, String path) throws IOException {
		// TODO  Check permissions
		String p = path(parent) + "/" + path;
		Resource r = search.search(p).iterator().next();
		if (r == null) {
			// Resources that don't exist are directories
			return new Inode(new FileHandle.FileHandleBuilder().build(p.getBytes()));
		}

		return new ResourceInode(r);
	}

	@Override
	public Inode link(Inode parent, Inode link, String path, Subject subject) throws IOException {
		// TODO  Check permissions
		return create(parent, Type.SYMLINK, path, subject,
				getModeForPermissions(search.search(path).iterator().next().getPermissions()));
	}

	@Override
	public DirectoryStream list(Inode inode, byte[] b, long ll) throws IOException {
		// TODO  Check permissions
		for (Resource r : search.search(path(inode) + "/*")) {
			Stat s = new Stat();
			s.setMode(getModeForPermissions(r.getPermissions()));
			// TODO l.add(new DirectoryEntry(nameForUri(r.getURI()), new ResourceInode(r), s));
		}

		// TODO return l;
		throw new UnsupportedOperationException();
	}

	@Override
	public Inode mkdir(Inode parent, String path, Subject subject, int mode) throws IOException {
		// TODO  Check permissions
		String p = path(parent) + path;
		if (dir.newDirectory(p)) {
			return new Inode(new FileHandle.FileHandleBuilder().build(p.getBytes()));
		} else {
			return null;
		}
	}

	/**
	 * Renames/moves a resource by creating a copy at the destination and
	 * deleting the original, following the same resource lookup and deletion
	 * patterns used by {@link #lookup} and {@link #remove}.
	 *
	 * <p>Content is transferred from the source resource via its
	 * {@link Resource#getInputStream() InputStream} when available.</p>
	 */
	@Override
	public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
		String srcPath = path(src) + "/" + oldName;
		String destPath = path(dest) + "/" + newName;

		Iterator<Resource> itr = search.search(srcPath).iterator();
		if (!itr.hasNext()) {
			return false;
		}

		Resource source = itr.next();

		T copy = factory.construct();
		copy.setURI(destPath);
		copy.getPermissions().update(source.getPermissions());

		InputStream in = source.getInputStream();
		if (in != null) {
			try {
				byte[] data = in.readAllBytes();
				copy.load(data, 0, data.length);
			} finally {
				in.close();
			}
		}

		del.delete(srcPath);
		return true;
	}

	@Override
	public Inode parentOf(Inode inode) throws IOException {
		String p = path(inode);

		// Remove trailing slashes
		while (p.lastIndexOf("/") == (p.length() - 1)) p = p.substring(0, p.length() - 1);

		// Remove last path segment
		p = p.substring(0, p.lastIndexOf("/"));

		Iterator<Resource> itr = search.search(p).iterator();
		Resource r = itr.hasNext() ? itr.next() : null;
		if (r == null) return new Inode(new FileHandle.FileHandleBuilder().build(p.getBytes()));
		return new ResourceInode(r);
	}

	@Override
	public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
		// TODO  Check permissions
//		return ((ResourceInode) inode).read(data, offset, count);
		return 0;
	}

	@Override
	public String readlink(Inode inode) throws IOException {
		// TODO  Use decoder to convert data based on request format
		//       IE, a user that saved a file with the .csv extension
		//       might request the same file with the .json extension,
		//       in which case it would be returned as json instead
		return ((ResourceInode) inode).getData().toString();
	}

	@Override
	public void remove(Inode parent, String path) throws IOException {
		// TODO  Check permissions
		del.delete(path(parent) + "/" + path);
	}

	@Override
	public Inode symlink(Inode parent, String path, String link, Subject subject, int mode) throws IOException {
		// TODO  Check permissions
		return create(parent, Type.SYMLINK, path, subject, mode);
	}

	@Override
	public WriteResult write(Inode inode, byte[] data, long offset, int count, StabilityLevel stabilityLevel)
			throws IOException {
		// TODO
		((ResourceInode) inode).load(data, offset, count);
		return null;
	}

	@Override
	public void commit(Inode inode, long offset, int count) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public Stat getattr(Inode inode) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setattr(Inode inode, Stat stat) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public nfsace4[] getAcl(Inode inode) throws IOException {
		log("GraphFileSystem: getAcl(" + inode + ")");
		return null;
	}

	@Override
	public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean hasIOLayout(Inode inode) throws IOException {
		log("GraphFileSystem: hasIOLayout(" + inode + ")");
		return false;
	}

	@Override
	public AclCheckable getAclCheckable() {
		return new AclCheckable() {
			@Override
			public Access checkAcl(Subject subject, Inode inode, int accessMask) throws IOException {
				throw new RuntimeException("Not implemented"); // TODO
			}
		};
	}

	@Override
	public NfsIdMapping getIdMapper() {
		log("GraphFileSystem: getIdMapper()");
		return null;
	}

	@Override
	public byte[] directoryVerifier(Inode inode) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getCaseInsensitive() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getCasePreserving() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Converts a Unix-style octal mode integer to a {@link Permissions} object.
	 *
	 * @param m The Unix mode bits (e.g., {@code 0755})
	 * @return A {@link Permissions} object representing the equivalent access settings
	 */
	protected static Permissions getPermissionsForMode(int m) {
		int mask = 7;
		int umask = mask << 6;
		int gmask = mask << 3;
		int omask = mask;
		int u = (m & umask) >> 6;
		int g = (m & gmask) >> 3;
		int o = (m & omask);
		return new Permissions(null,
								getSettingForMode(u),
								getSettingForMode(g),
								getSettingForMode(o));
	}

	/**
	 * Converts a {@link Permissions} object to a Unix-style octal mode integer.
	 *
	 * @param p The permissions to convert
	 * @return The Unix mode bits encoding the user, group, and other settings
	 */
	protected static int getModeForPermissions(Permissions p) {
		int u = getModeForSetting(p.getUserSetting());
		int g = getModeForSetting(p.getGroupSetting());
		int o = getModeForSetting(p.getOthersSetting());

		return (u << 6) + (g << 3) + o;
	}

	/**
	 * Maps a {@link Permissions.Setting} enum value to its Unix mode bit equivalent (0–7).
	 *
	 * @param s The permission setting to convert
	 * @return The corresponding Unix mode bits
	 */
	private static int getModeForSetting(Permissions.Setting s) {
		switch (s) {
			case EMPTY:
				return 0;
			case EXECUTE:
				return 1;
			case WRITE:
				return 2;
			case WRITE_EXECUTE:
				return 3;
			case READ:
				return 4;
			case READ_EXECUTE:
				return 5;
			case READ_WRITE:
				return 6;
			case READ_WRITE_EXECUTE:
				return 7;
		}

		return 0;
	}

	/**
	 * Maps a Unix mode bit value (0–7) to its {@link Permissions.Setting} equivalent.
	 *
	 * @param m The Unix mode bits (0–7)
	 * @return The corresponding {@link Permissions.Setting}
	 */
	private static Permissions.Setting getSettingForMode(int m) {
		switch (m) {
			case 0:
				return Permissions.Setting.EMPTY;
			case 1:
				return Permissions.Setting.EXECUTE;
			case 2:
				return Permissions.Setting.WRITE;
			case 3:
				return Permissions.Setting.WRITE_EXECUTE;
			case 4:
				return Permissions.Setting.READ;
			case 5:
				return Permissions.Setting.READ_EXECUTE;
			case 6:
				return Permissions.Setting.READ_WRITE;
			case 7:
				return Permissions.Setting.READ_WRITE_EXECUTE;
		}

		return Permissions.Setting.EMPTY;
	}

	/**
	 * Extracts the path string from an {@link Inode} by decoding its file ID bytes.
	 *
	 * @param n The inode whose path to extract
	 * @return The path string encoded in the inode's file handle
	 */
	private static String path(Inode n) { return path(n.getFileId()); }

	/**
	 * Decodes a byte array file ID to a path string.
	 *
	 * @param id The raw file handle bytes
	 * @return The path string
	 */
	private static String path(byte[] id) { return new String(id); }

	/**
	 * Extracts the file name from a full URI path by taking the substring after the last slash.
	 *
	 * @param uri The full URI path of the resource
	 * @return The file name portion of the URI
	 */
	private static String nameForUri(String uri) {
		return uri.substring(uri.lastIndexOf("/") + 1);
	}
}
