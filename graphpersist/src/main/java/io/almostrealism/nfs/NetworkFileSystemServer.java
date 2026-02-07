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

public class NetworkFileSystemServer {
	private final int port = 2049;
	
	private final OncRpcSvc nfs;

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
	
	public int getPort() { return port; }

	public void start() throws IOException {
		nfs.start();
	}
}
