package io.flowtree.www;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.SimpleWebServer;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * A FlowTree {@link Node} that embeds a NanoHTTPD {@link SimpleWebServer} to
 * serve static files from a local directory. When started, the node launches
 * the HTTP server bound to {@code localhost:80} and then delegates to
 * {@link Node#start()} to begin participating in the FlowTree job queue.
 *
 * <p>If the web server cannot bind to its port an {@link IOException} is
 * logged at error level and the node continues its startup sequence without
 * an HTTP server.
 *
 * @author  Michael Murray
 */
public class WebServerNode extends Node {

	/** The embedded NanoHTTPD web server managed by this node. */
    private final SimpleWebServer httpd;

	/**
	 * Constructs a new {@link WebServerNode} within the given node group. The
	 * node is created with zero initial jobs and zero maximum peers because
	 * its primary purpose is serving static HTTP content rather than executing
	 * FlowTree jobs. The web server will serve files from the specified
	 * directory.
	 *
	 * @param parent the {@link NodeGroup} this node belongs to
	 * @param id     unique numeric identifier for this node within the group
	 * @param dir    local directory path from which static files are served
	 */
    public WebServerNode(NodeGroup parent, int id, String dir) {
        super(parent, id, 0, 0);
        this.httpd = new SimpleWebServer("localhost", 80, new File(dir), false);
    }

	/**
	 * Starts the embedded NanoHTTPD web server and then starts this node's
	 * FlowTree participation loop. The server is started in blocking mode
	 * using {@link NanoHTTPD#SOCKET_READ_TIMEOUT}. If an {@link IOException}
	 * occurs the error is logged and the node continues to start.
	 *
	 * @return this node (returned by {@link Node#start()})
	 */
    public Node start() {
        try {
            this.httpd.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException e) {
            LoggerFactory.getLogger(WebServerNode.class).error("Could not start web server", e);
        }

        return super.start();
    }
}
