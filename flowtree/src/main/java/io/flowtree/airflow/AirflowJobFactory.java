/*
 * Copyright 2019 Michael Murray
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

package io.flowtree.airflow;

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.node.Client;
import org.almostrealism.util.KeyUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import org.almostrealism.io.ConsoleFeatures;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link JobFactory} that acts as an HTTP endpoint, accepting job submissions
 * from Apache Airflow (or any HTTP client) on port 7070 and converting them into
 * {@link AirflowJob} instances for execution by FlowTree nodes.
 *
 * <p>When the no-argument constructor is used, a single JVM-wide Jetty server is
 * started on port 7070. Subsequent constructions in the same JVM are silently
 * ignored — the first instance remains the singleton handler. HTTP GET requests
 * arriving at the server must supply a {@code cmd} query parameter; each request
 * creates one {@link AirflowJob} and returns its generated task id as the response
 * body.
 *
 * <p>The factory never reports completion ({@link #isComplete()} always returns
 * {@code false}) because it is designed to receive an unbounded stream of tasks
 * over its lifetime.
 *
 * @author  Michael Murray
 */
public class AirflowJobFactory extends AbstractHandler implements JobFactory, ConsoleFeatures {

	/** The JVM-wide singleton instance that owns the Jetty HTTP endpoint. */
	private static AirflowJobFactory defaultFactory;

	/** Priority of jobs produced by this factory. */
	private double pri = 1.0;

	/** Task identifier associated with this factory. */
	private String taskId;

	/** Sequence counter used internally. */
	private int i;

	/** Queue of pending jobs waiting to be dispatched via {@link #nextJob()}. */
	private final List jobs;

	/** Completion future for this factory (never completed). */
	private CompletableFuture<Void> future;

	/**
	 * Constructs a new {@link AirflowJobFactory} and starts the JVM-wide Jetty
	 * HTTP endpoint on port 7070 if one is not already running. This constructor
	 * is intended for standalone use when this factory is the primary entry point.
	 */
	public AirflowJobFactory() {
		initDefaultFactory(this);
		this.jobs = new ArrayList();
	}

	/**
	 * Constructs a new {@link AirflowJobFactory} with the specified task identifier.
	 * This constructor does <em>not</em> start an HTTP endpoint and is intended for
	 * use when the factory is reconstructed from an encoded string on a remote node.
	 *
	 * @param taskId  the task identifier to associate with this factory
	 */
	public AirflowJobFactory(String taskId) {
		this.taskId = taskId;
		this.jobs = new ArrayList();
	}

	/**
	 * Initialises the JVM-wide singleton HTTP endpoint. If a default factory has
	 * already been registered this method logs a warning and returns without
	 * starting a second server. Otherwise it records the given factory as the
	 * singleton and starts a Jetty {@link Server} on port 7070. If the port is
	 * already bound, a warning is logged and the endpoint is silently disabled.
	 *
	 * @param f  the factory instance to register as the default
	 */
	private static synchronized void initDefaultFactory(AirflowJobFactory f) {
		if (defaultFactory != null) {
			f.warn("AirflowJobFactory already initialized in this JVM -- skipping");
			return;
		}

		defaultFactory = f;

		Server server = new Server(7070);
		server.setHandler(defaultFactory);

		try {
			server.start();
		} catch (Exception e) {
			if (e instanceof BindException || e.getCause() instanceof BindException) {
				f.warn("Airflow HTTP port 7070 already in use -- Airflow endpoint disabled");
			} else {
				f.warn("Failed to start Airflow HTTP endpoint: " + e.getMessage());
			}
		}
	}

	/**
	 * Returns the task identifier associated with this factory.
	 *
	 * @return the task id string
	 */
	@Override
	public String getTaskId() { return this.taskId; }

	/**
	 * Returns the next pending {@link AirflowJob} that was enqueued by an HTTP
	 * request, removing it from the internal queue. Returns {@code null} if no
	 * jobs are currently pending.
	 *
	 * @return the next pending job, or {@code null} if the queue is empty
	 * @see io.flowtree.job.JobFactory#nextJob()
	 */
	@Override
	public Job nextJob() {
		if (this.jobs.size() > 0) return (Job) this.jobs.remove(0);
		return null;
	}

	/**
	 * Creates a {@link Job} from the given encoded data string by delegating to the
	 * current {@link io.flowtree.Server} or to
	 * {@link io.flowtree.Server#instantiateJobClass(String)} if no server is
	 * available via the current {@link Client}.
	 *
	 * @param data  encoded job string
	 * @return the decoded {@link Job} instance
	 * @see io.flowtree.job.JobFactory#createJob(java.lang.String)
	 */
	@Override
	public Job createJob(String data) {
		Client c = Client.getCurrentClient();

		if (c != null && c.getServer() != null)
			return c.getServer().createJob(data);
		else
			return io.flowtree.Server.instantiateJobClass(data);
	}

	/**
	 * Handles an incoming HTTP GET request from the Airflow endpoint. Reads the
	 * {@code cmd} query parameter and, if present, creates a new {@link AirflowJob}
	 * and adds it to the internal pending-job queue. The generated task id is
	 * written as the response body with a 200 OK status.
	 *
	 * @param target      the request target path
	 * @param baseRequest the Jetty base request object
	 * @param request     the servlet request
	 * @param response    the servlet response
	 * @throws IOException      if an I/O error occurs while writing the response
	 * @throws ServletException if the request cannot be handled
	 */
	@Override
	public void handle(String target, Request baseRequest,
					   HttpServletRequest request,
					   HttpServletResponse response) throws IOException, ServletException {
		String id = KeyUtils.generateKey();
		String cmd = request.getParameter("cmd");
		if (cmd == null) return;
		this.jobs.add(new AirflowJob(id, cmd));

		response.setContentType("text/html; charset=utf-8");
		response.setStatus(HttpServletResponse.SC_OK);
		response.getWriter().println(id);
		baseRequest.setHandled(true);
	}

	/**
	 * Returns a string encoding of this {@link AirflowJobFactory} containing only
	 * the class name. The task identifier is not included because the factory is
	 * stateless with respect to remote reconstruction.
	 *
	 * @return a String encoding of this {@link AirflowJobFactory} object.
	 */
	@Override
	public String encode() {
		return this.getClass().getName();
	}

	/**
	 * Restores state from a key/value pair. The only recognized key is {@code "id"},
	 * which sets the task identifier for this factory.
	 *
	 * @param key   the field name
	 * @param value the field value
	 * @see io.flowtree.job.JobFactory#set(java.lang.String, java.lang.String)
	 */
	@Override
	public void set(String key, String value) {
		if (key.equals("id")) {
			this.taskId = value;
		}
	}

	/**
	 * Returns a human-readable name for this factory including the task identifier.
	 *
	 * @return display name of this factory
	 */
	@Override
	public String getName() {
		String b = "Airflow Worker - " +
				this.taskId;
		return b;
	}

	/**
	 * Returns the completeness fraction of this factory. Always returns {@code 0}
	 * because an Airflow worker is perpetually ready to accept new commands.
	 *
	 * @return {@code 0}
	 */
	@Override
	public double getCompleteness() { return 0; }

	/**
	 * Always returns {@code false}. An {@link AirflowJobFactory} is never
	 * considered complete because its work is never over — it continually
	 * accepts new commands from the Airflow HTTP endpoint.
	 *
	 * @return {@code false}
	 */
	@Override
	public boolean isComplete() { return false; }

	/**
	 * Sets the dispatch priority for jobs produced by this factory.
	 *
	 * @param p the new priority value
	 */
	@Override
	public void setPriority(double p) { this.pri = p; }

	/**
	 * Returns the dispatch priority for jobs produced by this factory.
	 *
	 * @return the current priority value
	 */
	@Override
	public double getPriority() { return this.pri; }

	/**
	 * Returns the {@link CompletableFuture} associated with this factory's
	 * overall lifecycle. This future is never completed in normal operation.
	 *
	 * @return the lifecycle future
	 */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	/**
	 * Returns a human-readable description of this factory including the task
	 * identifier.
	 *
	 * @return string representation
	 */
	@Override
	public String toString() {
		return "AirflowJobFactory: " + this.taskId;
	}
}
