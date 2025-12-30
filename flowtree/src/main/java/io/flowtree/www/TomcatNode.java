package io.flowtree.www;

import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.LoggerFactory;

public class TomcatNode extends Node {
	private final Tomcat httpd;

	public TomcatNode(NodeGroup parent, int id) {
		super(parent, id, 0, 0);
		this.httpd = new Tomcat();
	}

	public Node start() {
		try {
			this.httpd.start();
		} catch (LifecycleException e) {
			LoggerFactory.getLogger(TomcatNode.class).error("Couldn't start Tomcat", e);
		}

		return super.start();
	}
}
