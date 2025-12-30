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

import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import org.dcache.nfs.vfs.FileHandle;
import org.dcache.nfs.vfs.Inode;

import java.io.IOException;
import java.io.InputStream;

public class ResourceInode extends Inode implements Resource {
    private final Resource r;

    public ResourceInode(Resource r) {
        super(new FileHandle.FileHandleBuilder().build(r.getURI().getBytes()));
        this.r = r;
    }

    @Override public void load(byte[] data, long offset, int len) { r.load(data, offset, len); }
    @Override public void load(IOStreams ioStreams) throws IOException { r.load(ioStreams); }
    @Override public void loadFromURI() throws IOException { r.loadFromURI(); }
    @Override public void send(IOStreams ioStreams) throws IOException { r.send(ioStreams); }
    @Override public void saveLocal(String s) throws IOException { r.saveLocal(s); }
    @Override public String getURI() { return r.getURI(); }
    @Override public void setURI(String s) { r.setURI(s); }
    @Override public Object getData() { return r.getData(); }
    @Override public InputStream getInputStream() { return r.getInputStream(); }
    @Override public Permissions getPermissions() { return r.getPermissions(); }
}
