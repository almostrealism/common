package io.flowtree.www;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.SimpleWebServer;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class WebServerNode extends Node {
    private final SimpleWebServer httpd;

    public WebServerNode(NodeGroup parent, int id, String dir) {
        super(parent, id, 0, 0);
        this.httpd = new SimpleWebServer("localhost", 80, new File(dir), false);
    }

    public Node start() {
        try {
            this.httpd.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException e) {
            LoggerFactory.getLogger(WebServerNode.class).error("Could not start web server", e);
        }

        return super.start();
    }
}
