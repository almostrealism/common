/*
 * Copyright 2021 Michael Murray
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

package io.flowtree.python;

import io.almostrealism.code.FunctionalWriter;
import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;
import org.almostrealism.util.KeyUtils;
import org.python.util.PythonInterpreter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A FlowTree {@code Job} that executes a Jython (Python on JVM) script.
 *
 * <p>The script is transmitted as a Base64-encoded string and decoded at execution time.
 * Each invocation of {@link #run()} creates a fresh {@link PythonInterpreter} via
 * try-with-resources to ensure proper cleanup. A thread-local interpreter pool is also
 * provided via {@link #getInterpreter()} for use cases that prefer reuse over isolation.</p>
 *
 * <p>Instances are created either directly or through the nested {@link Factory} class,
 * which integrates with the FlowTree job dispatch mechanism.</p>
 */
public class JythonJob implements Job {
	/** Thread-local pool of reusable Python interpreters for repeated script execution. */
	private static final ThreadLocal<PythonInterpreter> interpreters = new ThreadLocal<>();

	/** The FlowTree task identifier associated with this job. */
	private String taskId;
	/** The decoded Jython source code to be executed. */
	private String jython;

	/** Completion signal notified when {@link #run()} finishes. */
	private final CompletableFuture<Void> future = new CompletableFuture<>();

	/** Default no-arg constructor used during job deserialization. */
	public JythonJob() { }

	/**
	 * Creates a job with the given task identifier and Base64-encoded Jython source.
	 *
	 * @param taskId        the task identifier for this job
	 * @param jythonBase64  the Jython script encoded in Base64
	 */
	protected JythonJob(String taskId, String jythonBase64) {
		this.taskId = taskId;
		set("code", jythonBase64);
	}

	public String getJython() { return jython; }
	public void setJython(String jython) { this.jython = jython; }

	@Override
	public String getTaskId() {
		return taskId;
	}

	@Override
	public String getTaskString() {
		return null;
	}

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	@Override
	public void run() {
		int index = 0;

		try (PythonInterpreter pyInterp = new PythonInterpreter()) {
			pyInterp.exec(getJython());
		}

		future.complete(null);
	}

	@Override
	public String encode() {
		return this.getClass().getName() +
				":code=" +
				Base64.getEncoder().encodeToString(getJython().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void set(String k, String v) {
		if (k.equals("code")) {
			setJython(Optional.ofNullable(v).map(s -> new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)).orElse(null));
		} else {
			throw new IllegalArgumentException("");
		}
	}

	/**
	 * A FlowTree {@code AbstractJobFactory} that produces {@link JythonJob} instances
	 * from a Base64-encoded Jython script.
	 *
	 * <p>Each call to {@link #nextJob()} returns a new {@code JythonJob} pre-loaded with
	 * the encoded script, ready for dispatch to a FlowTree worker node.</p>
	 */
	public static class Factory extends AbstractJobFactory {
		/** Creates a factory with a freshly generated task key and no script pre-loaded. */
		public Factory() { super(KeyUtils.generateKey()); }

		/**
		 * Creates a factory pre-loaded with the specified Jython script.
		 *
		 * @param jython the Jython source code to encode and distribute as jobs
		 */
		public Factory(String jython) {
			this();
			this.set("code", Base64.getEncoder().encodeToString(jython.getBytes(StandardCharsets.UTF_8)));
		}

		@Override
		public Job nextJob() {
			return new JythonJob(getTaskId(), get("code"));
		}

		@Override
		public double getCompleteness() {
			return 0;
		}

		@Override
		public String toString() {
			return super.encode();
		}
	}

	/**
	 * Returns the thread-local {@link PythonInterpreter}, creating it on first access for the current thread.
	 *
	 * @return the interpreter for the calling thread
	 */
	protected static PythonInterpreter getInterpreter() {
		if (interpreters.get() == null) {
			interpreters.set(new PythonInterpreter());
		}

		return interpreters.get();
	}

	/**
	 * Executes a Jython instruction on the thread-local interpreter and returns any output written
	 * to stdout or stderr.
	 *
	 * @param instruction the Jython source code to execute
	 * @return the combined stdout/stderr output produced by the script
	 */
	public static String execute(String instruction) {
		StringBuilder buf = new StringBuilder();
		getInterpreter().setOut(new FunctionalWriter(buf::append));
		getInterpreter().setErr(new FunctionalWriter(buf::append));
		getInterpreter().exec(instruction);
		return buf.toString();
	}

	/**
	 * Closes and removes the thread-local interpreter for the current thread.
	 *
	 * <p>This method should be called when the calling thread is done executing Jython scripts
	 * to ensure the interpreter's resources (e.g., the embedded Python runtime) are released.</p>
	 */
	public static void closeInterpreter() {
		PythonInterpreter interpreter = interpreters.get();

		if (interpreter != null) {
			interpreter.close();
			interpreters.remove();
		}
	}
}
