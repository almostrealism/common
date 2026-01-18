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

package org.almostrealism.io;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Represents the output of a job execution with associated metadata.
 *
 * <p>JobOutput stores job results along with authentication credentials
 * and timing information. It supports both Java serialization (via {@link Externalizable})
 * and string-based encoding/decoding for transmission.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create job output
 * JobOutput output = new JobOutput("task-123", "user", "pass", "result data");
 * output.setTime(System.currentTimeMillis());
 *
 * // Encode for transmission
 * String encoded = output.encode();
 *
 * // Decode on receiving end
 * JobOutput received = JobOutput.decode(encoded);
 * String result = received.getOutput();
 * }</pre>
 *
 * @author Michael Murray
 * @see OutputHandler
 */
public class JobOutput implements Externalizable {
	/** Separator used in string encoding between fields. */
	public static final String ENTRY_SEPARATOR = "::";

	private String taskId;
	private String user, passwd, output;
	private long time;

	/**
	 * Default constructor for externalization support.
	 */
	public JobOutput() {
		this.taskId = "";
		this.user = "";
		this.passwd = "";
		this.output = "";
	}

	/**
	 * Creates a new JobOutput with the specified fields.
	 *
	 * @param taskId the task identifier
	 * @param user the username
	 * @param passwd the password
	 * @param output the job output data
	 */
    public JobOutput(String taskId, String user, String passwd, String output) {
		this.taskId = taskId;
        this.user = user;
        this.passwd = passwd;
        setOutput(output);
    }

    /** Sets the task identifier. */
    public void setTaskId(String taskId) { this.taskId = taskId; }
    /** Returns the task identifier. */
    public String getTaskId() { return this.taskId; }

    /** Sets the username. */
    public void setUser(String user) { this.user = user; }
    /** Sets the password. */
    public void setPassword(String passwd) { this.passwd = passwd; }
    /** Sets the output data. */
    public void setOutput(String output) { this.output = output; }
    /** Sets the timestamp. */
    public void setTime(long time) { this.time = time; }

    /** Returns the username. */
    public String getUser() { return this.user; }
    /** Returns the password. */
    public String getPassword() { return this.passwd; }
    /** Returns the output data. */
    public String getOutput() { return this.output; }
    /** Returns the timestamp. */
    public long getTime() { return this.time; }

    /**
     * Encodes this JobOutput as a string for transmission.
     * Uses {@link #ENTRY_SEPARATOR} to delimit fields.
     *
     * @return the encoded string representation
     * @see #decode(String)
     */
    public String encode() {
		return this.getClass().getName() + ENTRY_SEPARATOR +
				this.taskId + ENTRY_SEPARATOR +
				this.user + ENTRY_SEPARATOR +
				this.passwd + ENTRY_SEPARATOR +
				this.time + ENTRY_SEPARATOR +
				this.getOutput();
    }

    /**
     * Decodes a string representation back into a JobOutput instance.
     *
     * @param data the encoded string from {@link #encode()}
     * @return the decoded JobOutput, or null if decoding fails
     * @see #encode()
     */
    public static JobOutput decode(String data) {
		int index = data.indexOf(ENTRY_SEPARATOR);
		String className = data.substring(0, index);
		
		JobOutput j = null;
		
		try {
			j = (JobOutput) Class.forName(className).newInstance();
			
			boolean end = false;
			
			i: for (int i = 0; !end && i <= 4; i++) {
				data = data.substring(index + ENTRY_SEPARATOR.length());
				index = data.indexOf(ENTRY_SEPARATOR);
				
				if (data.charAt(index + ENTRY_SEPARATOR.length()) == '/') index = data.indexOf(ENTRY_SEPARATOR, index + ENTRY_SEPARATOR.length());
				
				String s = null;
				
				if (index < 0) {
					s = data;
					end = true;
				} else {
					s = data.substring(0, index);
				}
				
				if (i == 0)
					j.setTaskId(s);
				else if (i == 1)
					j.setUser(s);
				else if (i == 2)
					j.setPassword(s);
				else if (i == 3)
					j.setTime(Long.parseLong(s));
				else if (i == 4)
					j.setOutput(data);
				else
					break i;
			}
		} catch (Exception e) {
			System.out.println("JobOutput.decode: " + e);
			e.printStackTrace();
		}
		
		return j;
    }
    
	/**
	 * This method writes three UTF strings: the username, password, and output data
	 * stored by this JobOutput object. A subclass of this class should store the username
	 * and password in addition to whatever data is included.
	 * 
	 * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeUTF(this.taskId);
		out.writeUTF(this.user);
		out.writeUTF(this.passwd);
		out.writeUTF(this.output);
		out.writeLong(this.time);
		out.writeObject(null);
	}

	/**
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */
	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		this.taskId = in.readUTF();
		this.user = in.readUTF();
		this.passwd = in.readUTF();
		this.output = in.readUTF();
		this.time = in.readLong();
		
		Object o = in.readObject();
		
		if (o != null)
			System.out.println("JobOutput: Received " + o + " during external read.");
	}

	@Override
	public String toString() { return this.getOutput(); }
}
