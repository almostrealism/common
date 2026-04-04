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

import io.almostrealism.relation.Graph;
import io.almostrealism.resource.Resource;
import org.dcache.nfs.ExportFile;
import org.dcache.nfs.v3.MountServer;
import org.dcache.nfs.v3.NfsServerV3;
import org.dcache.nfs.v4.MDSOperationExecutor;
import org.dcache.nfs.v4.NFSServerV41;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.dcache.oncrpc4j.rpc.OncRpcProgram;
import org.dcache.oncrpc4j.rpc.OncRpcSvc;
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder;

import java.io.IOException;

/**
 * Starts an NFS server (v3 and v4.1) backed by a {@link GraphFileSystem}.
 *
 * <p>On construction, the server registers NFSv4.1, NFSv3, and Mount protocol handlers
 * on the standard NFS port (2049) via ONC-RPC over TCP. Call {@link #start()} to begin
 * accepting connections.</p>
 */
public class NetworkFileSystemServer {
	/** The NFS port on which all protocol handlers are registered. */
	private final int port = 2049;

	/** The underlying ONC-RPC service that listens for and dispatches NFS requests. */
	private final OncRpcSvc nfs;

	/**
	 * Constructs the NFS server, wiring together the resource graph and file system manager
	 * into NFS v3, v4.1, and Mount protocol handlers.
	 *
	 * @param graph The resource graph backing the virtual file system
	 * @param fs    The file system manager providing factory, search, directory, and deletion services
	 * @throws IOException If the ONC-RPC service cannot be built
	 */
	public NetworkFileSystemServer(Graph<Resource> graph, FileSystemManager fs) throws IOException {
		VirtualFileSystem vfs = new GraphFileSystem(graph, fs, fs, fs, fs);

		nfs = new OncRpcSvcBuilder().withPort(port).withTCP().withAutoPublish().withWorkerThreadIoStrategy().build();

		ExportFile exportFile = null; // TODO specify file with export entries

		NFSServerV41 nfs4 = new NFSServerV41(new MDSOperationExecutor(), null /*new DeviceManager()*/, vfs, exportFile);
		NfsServerV3 nfs3 = new NfsServerV3(exportFile, vfs);
		MountServer ms = new MountServer(exportFile, vfs);

		nfs.register(new OncRpcProgram(100003, 4), nfs4);
		nfs.register(new OncRpcProgram(100003, 3), nfs3);
		nfs.register(new OncRpcProgram(100005, 3), ms);
	}
	
	/**
	 * Returns the TCP port on which the NFS service listens.
	 *
	 * @return The NFS port (always {@code 2049})
	 */
	public int getPort() { return port; }

	/**
	 * Starts the ONC-RPC service and begins accepting NFS client connections.
	 *
	 * @throws IOException If the service cannot bind to the port
	 */
	public void start() throws IOException {
		nfs.start();
	}
}
