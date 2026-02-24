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

public class JythonJob implements Job {
	private static final ThreadLocal<PythonInterpreter> interpreters = new ThreadLocal<>();

	private String taskId;
	private String jython;

	private final CompletableFuture<Void> future = new CompletableFuture<>();

	public JythonJob() { }

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

	public static class Factory extends AbstractJobFactory {
		public Factory() { super(KeyUtils.generateKey()); }

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

	protected static PythonInterpreter getInterpreter() {
		if (interpreters.get() == null) {
			interpreters.set(new PythonInterpreter());
		}

		return interpreters.get();
	}

	public static String execute(String instruction) {
		StringBuilder buf = new StringBuilder();
		getInterpreter().setOut(new FunctionalWriter(buf::append));
		getInterpreter().setErr(new FunctionalWriter(buf::append));
		getInterpreter().exec(instruction);
		return buf.toString();
	}

	public static void closeInterpreter() {
		PythonInterpreter interpreter = interpreters.get();

		if (interpreter != null) {
			interpreter.close();
			interpreters.remove();
		}
	}
}
