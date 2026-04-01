package io.flowtree.www;

import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.LoggerFactory;

/**
 * A FlowTree {@link Node} that embeds an Apache Tomcat HTTP server. When
 * started, the node first starts the embedded Tomcat instance and then
 * delegates to {@link Node#start()} to begin participating in the FlowTree
 * job queue. This allows a single process to act as both a FlowTree worker
 * and a servlet/web-application container.
 *
 * <p>If Tomcat fails to start a {@link LifecycleException} is logged at
 * error level and the node continues its startup sequence without an HTTP
 * server.
 *
 * @author  Michael Murray
 */
public class TomcatNode extends Node {

	/** The embedded Apache Tomcat server managed by this node. */
	private final Tomcat httpd;

	/**
	 * Constructs a new {@link TomcatNode} within the given node group. The
	 * node is created with zero initial jobs and zero maximum peers because
	 * its primary purpose is serving HTTP requests rather than executing
	 * FlowTree jobs.
	 *
	 * @param parent the {@link NodeGroup} this node belongs to
	 * @param id     unique numeric identifier for this node within the group
	 */
	public TomcatNode(NodeGroup parent, int id) {
		super(parent, id, 0, 0);
		this.httpd = new Tomcat();
	}

	/**
	 * Starts the embedded Tomcat server and then starts this node's FlowTree
	 * participation loop. If Tomcat throws a {@link LifecycleException} the
	 * error is logged and the node continues to start.
	 *
	 * @return this node (returned by {@link Node#start()})
	 */
	public Node start() {
		try {
			this.httpd.start();
		} catch (LifecycleException e) {
			LoggerFactory.getLogger(TomcatNode.class).error("Couldn't start Tomcat", e);
		}

		return super.start();
	}
}
