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

package io.flowtree;

import io.almostrealism.persist.LocalResource;
import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import io.flowtree.fs.DistributedResource;
import io.flowtree.fs.ImageResource;
import io.flowtree.fs.ResourceDistributionTask;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.color.RGB;
import org.almostrealism.texture.GraphicsConverter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the in-memory resource cache and all resource loading operations on
 * behalf of a {@link Server} instance.  This class centralises cache look-up,
 * remote resource streaming, image loading, and URI parsing so that
 * {@link Server} can delegate these concerns without exposing them in its own
 * body.
 *
 * <p>Instances are package-private and created exclusively by {@link Server}.
 */
class ServerResourceManager {

    /** The owning {@link Server} whose cache and peer list this manager operates on. */
    private final Server server;

    /** Maximum number of resources to retain in the in-memory cache. */
    private int maxCache;

    /**
     * Optional directory path used to persist cache entries to local storage.
     * {@code null} if cache logging is disabled.
     */
    private final String logCache;

    /** In-memory resource cache keyed by URI. */
    private final Map<String, Object> cache;

    /** URIs currently being loaded, used to prevent duplicate concurrent load operations. */
    private final List<String> loading;

    /**
     * Constructs a new {@link ServerResourceManager} for the given {@link Server}.
     *
     * @param server    The owning server instance.
     * @param maxCache  Initial maximum number of resources to retain in the cache.
     * @param logCache  Directory path for persisting cached resources, or {@code null}
     *                  to disable cache logging.
     */
    ServerResourceManager(Server server, int maxCache, String logCache) {
        this.server = server;
        this.maxCache = maxCache;
        this.logCache = logCache;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>());
        this.loading = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Sets the maximum number of resources to retain in the in-memory cache.
     *
     * @param max  Maximum cache size.
     */
    void setMaxCache(int max) { this.maxCache = max; }

    /**
     * Returns the maximum number of resources retained in the in-memory cache.
     *
     * @return  Maximum cache size.
     */
    int getMaxCache() { return this.maxCache; }

    /**
     * Returns the raw cache map, allowing the owning {@link Server} to look up entries
     * directly (e.g. from the resource-server thread).
     *
     * @return  The live cache map, keyed by URI.
     */
    Map<String, Object> getCache() { return this.cache; }

    /**
     * Returns {@code true} if the in-memory cache contains an entry for the given URI.
     *
     * @param uri  URI to test.
     * @return  {@code true} if the URI is cached, {@code false} otherwise.
     */
    boolean cacheContains(String uri) { return this.cache.containsKey(uri); }

    /**
     * Retrieves a resource from the cache by URI.  If the resource is currently
     * being loaded by another thread, this method blocks with an exponential
     * back-off until the load completes and the entry appears in the cache.
     *
     * @param uri  URI of the resource to look up.
     * @return  The cached resource object, or {@code null} if the URI is not in the cache.
     */
    Object loadFromCache(String uri) {
        Object s = null;

        for (int i = 0; ; ) {
            s = this.cache.get(uri);

            if (this.loading.contains(uri)) {
                try {
                    int sleep = 1000;

                    if (i == 0) {
                        sleep = 1000;
                        i++;
                    } else if (i == 1) {
                        sleep = 5000;
                        i++;
                    } else if (i == 2) {
                        sleep = 10000;
                        i++;
                    } else if (i < 6) {
                        sleep = 10000 * (int) Math.pow(2, i);
                        i++;
                    } else {
                        sleep = 1200000;
                    }

                    Thread.sleep(sleep);

                    server.log("Waited " + sleep / 1000.0 + " seconds for " + uri);
                } catch (InterruptedException ie) {
                    // Interrupted — retry immediately.
                }
            } else if (s == null) {
                return null;
            } else {
                this.loading.remove(uri);
                return s;
            }
        }
    }

    /**
     * Loads the resource identified by the given URI via the distributed file system.
     * The resource stream is obtained from a connected peer and the result is cached.
     *
     * @param uri  URI of the resource to load.
     * @return  The loaded {@link Resource}.
     * @throws IOException  If an IO error occurs while loading the resource.
     */
    Resource loadResource(String uri) throws IOException {
        IOStreams io = this.getResourceStream(uri, null);
        Resource res = ResourceDistributionTask.getCurrentTask().getResource(uri);
        if (res == null) res = DistributedResource.createDistributedResource(uri);
        return this.loadResourceFromIO(res, io, false);
    }

    /**
     * Loads the resource identified by the given URI from the distributed file system,
     * optionally falling back to the local filesystem.
     *
     * @param uri       URI of the resource to load.
     * @param tryLocal  If {@code true} and the resource is not found remotely, attempts
     *                  to load it from the local filesystem via its URI.
     * @return  The loaded {@link Resource}, or {@code null} if not found and
     *          {@code tryLocal} is {@code false}.
     */
    Resource loadResource(String uri, boolean tryLocal) {
        Resource res = ResourceDistributionTask.getCurrentTask().getResource(uri);
        if (res != null) return res;
        if (!tryLocal) return null;

        res = new LocalResource(uri);

        try {
            return this.loadResource(res);
        } catch (IOException e) {
            server.warn("IO error loading local resource (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * Loads the given {@link Resource}, checking the cache first and fetching from
     * a remote peer if necessary. The result is added to the cache.
     *
     * @param r  The resource descriptor to load.
     * @return  The loaded {@link Resource}.
     * @throws IOException  If an IO error occurs while loading the resource.
     */
    Resource loadResource(Resource r) throws IOException {
        return this.loadResource(r, null, false);
    }

    /**
     * Loads the given {@link Resource}, optionally skipping the cache.
     *
     * @param r        The resource descriptor to load.
     * @param noCache  If {@code true}, the loaded resource will not be stored in the cache.
     * @return  The loaded {@link Resource}.
     * @throws IOException  If an IO error occurs while loading the resource.
     */
    Resource loadResource(Resource r, boolean noCache) throws IOException {
        return this.loadResource(r, null, noCache);
    }

    /**
     * Loads the given {@link Resource}, excluding a specific host when searching for the
     * resource stream among connected peers.
     *
     * @param r        The resource descriptor to load.
     * @param exclude  Hostname of a peer to skip when querying for the resource stream.
     * @param noCache  If {@code true}, the loaded resource will not be stored in the cache.
     * @return  The loaded {@link Resource}, or {@code null} if already in progress.
     * @throws IOException  If an IO error occurs while loading the resource.
     */
    Resource loadResource(Resource r, String exclude, boolean noCache) throws IOException {
        if (r instanceof DistributedResource && this.loading.contains(r.getURI()))
            return null;

        Object o = this.loadFromCache(r.getURI());
        if (o != null) return (Resource) o;

        this.loading.add(r.getURI());

        IOStreams io = this.getResourceStream(r.getURI(), exclude);
        return this.loadResourceFromIO(r, io, noCache);
    }

    /**
     * Loads a resource using the provided {@link IOStreams}, or from the resource's own
     * URI if the streams are {@code null}. After loading, the resource is optionally
     * placed in the in-memory cache and persisted to the log-cache directory.
     *
     * @param r        The resource descriptor to populate.
     * @param io       IO streams to read the resource data from, or {@code null} to load
     *                 directly from the resource's URI.
     * @param noCache  If {@code true}, the loaded resource will not be stored in the cache.
     * @return  The loaded {@link Resource}.
     * @throws IOException  If an IO error occurs while loading the resource.
     */
    Resource loadResourceFromIO(Resource r, IOStreams io, boolean noCache) throws IOException {
        if (io != null) {
            r.load(io);
        } else if (r.getURI() != null && !r.getURI().startsWith("resource:")) {
            r.loadFromURI();
        } else {
            return DistributedResource.createDistributedResource(r.getURI());
        }

        if (!noCache) synchronized (this.cache) {
            Object c = null;

            if (this.cache.size() >= this.maxCache)
                c = this.cache.keySet().iterator().next();

            if (c != null) {
                this.cache.remove(c);
                server.log("Removed cache of " + c);
            }

            this.cache.put(r.getURI(), r);
        }

        if (this.logCache != null) {
            try {
                String output = "cache/" + System.currentTimeMillis();
                server.log("Writing " + output);
                r.saveLocal(output);
                server.log("Done writing " + output);
            } catch (IOException ioe) {
                server.warn("Error writing cache: " + ioe.getMessage());
            }
        }

        this.loading.remove(r.getURI());

        return r;
    }

    /**
     * Loads the full image at the given URI and returns it as an {@link RGB} array.
     *
     * @param uri  URI of the image to load.
     * @return  A two-dimensional {@link RGB} array containing the image data, or
     *          {@code null} if the image could not be loaded.
     */
    RGB[][] loadImage(String uri) {
        return this.loadImage(uri, 0, 0, 0, 0, false, false);
    }

    /**
     * Loads the image at the given URI, optionally suppressing the return value.
     * This can be used to pre-populate the cache without the overhead of converting
     * image data to an {@link RGB} array.
     *
     * @param uri       URI of the image to load.
     * @param noReturn  If {@code true}, loads the image into the cache but returns
     *                  {@code null} instead of the pixel data.
     * @return  A two-dimensional {@link RGB} array, or {@code null} if loading fails
     *          or {@code noReturn} is {@code true}.
     */
    RGB[][] loadImage(String uri, boolean noReturn) {
        return this.loadImage(uri, 0, 0, 0, 0, noReturn, false);
    }

    /**
     * Loads a rectangular sub-region of the image at the given URI.
     *
     * @param uri  URI of the image to load.
     * @param x    X offset of the sub-region within the image.
     * @param y    Y offset of the sub-region within the image.
     * @param w    Width of the sub-region in pixels.
     * @param h    Height of the sub-region in pixels.
     * @return  A two-dimensional {@link RGB} array for the requested sub-region, or
     *          {@code null} if the image could not be loaded.
     */
    RGB[][] loadImage(String uri, int x, int y, int w, int h) {
        return this.loadImage(uri, x, y, w, h, false, false);
    }

    /**
     * Loads an image using the cache system managed by the owning {@link Server}.
     * To load an image using SCP, the uri must take the form:
     *   scp://host|user|passwd/path
     * Where host is the hostname or ip of the host to contact, user and passwd
     * are the user and password to authenticate with, and path is the absolute
     * path of the resource to load. For example:
     *   scp://localhost|root|secure/usr/local/images/test.jpeg
     * would log into localhost as root with password "secure" and download the
     * jpeg image /usr/local/images/test.jpeg
     *
     * <p>An image can be loaded as a resource from a resource server running on
     * a remote {@link Server} instance. To achieve this, preface the uri to load with
     * resource://. For example:
     *   resource://10.0.0.1/http://asdf.com/image.jpeg
     * would load the image found at http://asdf.com/image.jpeg from the resource
     * server running on 10.0.0.1, instead of from the actual site.
     *
     * @param uri       URI of resource (starting with http://, scp://, or resource://).
     * @param ix        X offset of the sub-region within the image.
     * @param iy        Y offset of the sub-region within the image.
     * @param iw        Width of the sub-region in pixels.
     * @param ih        Height of the sub-region in pixels.
     * @param noReturn  Do not convert data to RGB and return it. Simply load data to cache.
     * @param noCache   Do not cache the data loaded by this method. Simply load, convert, and return.
     * @return  An RGB[][] containing the image data.
     */
    RGB[][] loadImage(String uri, int ix, int iy, int iw, int ih,
                      boolean noReturn, boolean noCache) {
        ImageResource res = new ImageResource(uri, null, new Permissions());
        res.setWidth(iw);
        res.setHeight(ih);
        res.setX(ix);
        res.setY(iy);

        try {
            res = (ImageResource) this.loadResource(res, noCache);
        } catch (IOException ioe) {
            server.warn("Error loading image (" + ioe.getMessage() + ")");
            res = null;
        }

        if (res == null) return null;
        int[] data = (int[]) res.getData();

        if (!noReturn)
            return GraphicsConverter.convertToRGBArray(data, 2, 0, 0, data[0], data[1], data[0]);
        else
            return null;
    }

    /**
     * Queries all connected peers for a resource stream matching the given URI.
     *
     * @param uri  URI of the resource to request.
     * @return  An open {@link IOStreams} from the first peer that has the resource,
     *          or {@code null} if no peer can satisfy the request.
     */
    IOStreams getResourceStream(String uri) {
        return this.getResourceStream(uri, null);
    }

    /**
     * Queries all connected peers (except the excluded host) for a resource stream
     * matching the given URI.
     *
     * @param uri      URI of the resource to request.
     * @param exclude  Hostname of the peer to skip, or {@code null} to query all peers.
     * @return  An open {@link IOStreams} from the first qualifying peer, or {@code null}
     *          if no peer can satisfy the request.
     */
    IOStreams getResourceStream(String uri, String exclude) {
        IOStreams io = null;

        NodeProxy[] p = server.getNodeGroup().getServers();

        i: for (int i = 0; i < p.length; i++) {
            String ad = p[i].toString();
            int adi = ad.lastIndexOf("/");
            if (adi > 0) ad = ad.substring(adi + 1);
            if (ad.equals(exclude)) continue i;

            try {
                Message m = new Message(Message.ResourceRequest, -2, p[i]);
                m.setString(uri);
                String s = (String) m.send(-1);

                if (s != null) {
                    io = server.parseResourceUri(s);
                    if (io != null) return io;
                }
            } catch (IOException ioe) {
                server.warn("Error making resource request (" + ioe.getMessage() + ")");
            }
        }

        return io;
    }

    /**
     * Opens a direct TCP connection to a resource server at the specified host and port
     * and requests the resource at the given URI path.
     *
     * @param host  Hostname or IP address of the resource server.
     * @param port  Port number of the resource server.
     * @param uri   URI path of the resource to request.
     * @return  An open {@link IOStreams} if the server has the resource, or {@code null}
     *          if the host is localhost/empty or the server reports the resource is absent.
     * @throws IOException  If a network error occurs while connecting or communicating.
     */
    IOStreams getResourceStream(String host, int port, String uri) throws IOException {
        if (host == null || host.equals("") || host.equals("localhost"))
            return null;

        server.log("Opening resource stream to " + host + " on " + port + " for " + uri);

        Socket s = new Socket(host, port);
        IOStreams io = new IOStreams();
        io.in = new DataInputStream(s.getInputStream());
        io.out = new DataOutputStream(s.getOutputStream());

        io.out.writeUTF(uri);

        server.log("Wrote request for " + uri);

        if (io.in.readInt() > 0)
            return io;
        else
            return null;
    }
}
