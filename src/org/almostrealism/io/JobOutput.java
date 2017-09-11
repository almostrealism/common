/*
 * Copyright 2017 Michael Murray
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
 * A {@link JobOutput} stores three string values: username, password, and output data
 * to be sent to the DB Server.
 * 
 * @author  Michael Murray
 */
public class JobOutput implements Externalizable {
  private String user, passwd, output;
  private long time;
  
	public JobOutput() {
		this.user = "";
		this.passwd = "";
		this.output = "";
	}
	
    public JobOutput(String user, String passwd, String output) {
        this.user = user;
        this.passwd = passwd;
        this.output = output;
    }
    
    public void setUser(String user) { this.user = user; }
    public void setPassword(String passwd) { this.passwd = passwd; }
    public void setOutput(String output) { this.output = output; }
    public void setTime(long time) { this.time = time; }
    
    public String getUser() { return this.user; }
    public String getPassword() { return this.passwd; }
    public String getOutput() { return this.output; }
    public long getTime() { return this.time; }
    
    public String encode() {
    		StringBuffer b = new StringBuffer();
    		b.append(this.getClass().getName() + ":");
    		b.append(this.user + ":");
    		b.append(this.passwd + ":");
    		b.append(this.time + ":");
    		b.append(this.getOutput());
    		
    		return b.toString();
    }
    
    public static JobOutput decode(String data) {
    		int index = data.indexOf(":");
		String className = data.substring(0, index);
		
		JobOutput j = null;
		
		try {
			j = (JobOutput)Class.forName(className).newInstance();
			
			boolean end = false;
			
			i: for (int i = 0; !end && i < 3; i++) {
				data = data.substring(index + 1);
				index = data.indexOf(":");
				
				if (data.charAt(index + 1) == '/') index = data.indexOf(":", index + 1);
				
				String s = null;
				
				if (index <= 0) {
					s = data;
					end = true;
				} else {
					s = data.substring(0, index);
				}
				
				if (i == 0)
					j.setUser(s);
				else if (i == 1)
					j.setPassword(s);
				else if (i == 2)
					j.setTime(Long.parseLong(s));
				else if (i == 3)
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
	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeUTF(this.user);
		out.writeUTF(this.passwd);
		out.writeUTF(this.output);
		out.writeLong(this.time);
		out.writeObject(null);
	}

	/**
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		this.user = in.readUTF();
		this.passwd = in.readUTF();
		this.output = in.readUTF();
		this.time = in.readLong();
		
		Object o = in.readObject();
		
		if (o != null)
			System.out.println("JobOutput: Recieved " + o + " during external read.");
	}
	
	public String toString() { return this.getOutput(); }
}
