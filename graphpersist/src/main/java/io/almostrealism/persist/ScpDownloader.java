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

package io.almostrealism.persist;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Map;

public class ScpDownloader implements UserInfo {
	private static final int maxCache = 5;
	private static Map cache;
	
	private final JSch sch;
	private Session session;
	private final String host;
	private final String user;
	private final String passwd;
	
	private File ddir;
	private int retry = 3;
	
	private boolean streamOpen = false;
	
	public ScpDownloader(String host, String user, String passwd) throws IOException {
		this.host = host;
		this.user = user;
		this.passwd = passwd;
		
		this.sch = new JSch();
		
		i: for (int i = 0; i < this.retry; i++) {
			if (i > 0) System.out.println("ScpDownloader: Retrying...");
			
			try {
				this.init();
				break;
			} catch (IOException ioe) {
				this.dispose();
				if (i >= this.retry - 1) throw ioe;
			}
		}
		
		this.setDownloadDir("temp/");
	}
	
	public void setDownloadDir(String dir) { this.ddir = new File(dir); }
	public void setRetry(int retry) { this.retry = retry; }
	
	public void init() throws IOException {
		try {
			this.session = this.sch.getSession(this.user, this.host, 22);
			this.session.setUserInfo(this);
			this.session.connect();
		} catch (JSchException e) {
			System.out.println("ScpDownloader (INIT): " + e.getMessage());
			throw new IOException("JSCP Error -- " + e.getMessage());
		}
	}
	
	public void dispose() {
		if (this.session != null) {
			this.session.disconnect();
			this.session = null;
		}
	}
	
	public synchronized void download(String file, OutputStream fout) throws IOException {
		while (this.streamOpen) {
			try {
				this.wait();
			} catch (InterruptedException ie) { return; }
		}
		
		String orig = file;
		
		String dir = "~/";
		int index = file.lastIndexOf("/");
		
		if (index > 0) {
			dir = file.substring(0, index + 1);
			file = file.substring(index + 1);
		}
		
		ScpDownloader.this.streamOpen = true;
		
		i: for (int i = 0; i < ScpDownloader.this.retry; i++) {
			if (i > 0) System.out.println("ScpDownloader: Retrying...");
			
			boolean done = false;
			
			try {
//				TODO  This class is missing
//				ScpFromMessage scp = new ScpFromMessage(this.session, orig, fout, true);
//				scp.execute();
				
				done = true;
				System.out.println("ScpDownloader: Done");
				fout.flush();
				break;
			} catch (IOException e) {
				System.out.println("ScpDownloader: " + e.getMessage());
				if (done) break;
//			} catch (JSchException jsch) {
//				System.out.println("ScpDownloader: " + jsch.getMessage());
			}
			
			ScpDownloader.this.dispose();
			
			try {
				ScpDownloader.this.init();
			} catch (IOException e) { }
		}
		
		ScpDownloader.this.streamOpen = false;
		this.notify();
	}
	
	public String download(String file) throws IOException {
		String orig = file;
		
		String dir = "~/";
		int index = file.lastIndexOf("/");
		
		if (index > 0) {
			dir = file.substring(0, index + 1);
			file = file.substring(index + 1);
		}
		
		long fn = System.currentTimeMillis();
		String local = null;
		index = file.lastIndexOf(".");
		
		if (index > 0)
			local = fn + file.substring(index);
		else
			local = fn + ".txt";
		
		File ofile = new File(this.ddir, local);
		OutputStream out = new FileOutputStream(ofile);
		this.download(orig, out);
		
		return ofile.getCanonicalPath();
	}
	
	public static synchronized ScpDownloader getDownloader(String host, String user, String passwd) throws IOException {
		if (ScpDownloader.cache == null) ScpDownloader.cache = new Hashtable();
		
		String s = host + "|" + user + "|" + passwd;
		
		if (ScpDownloader.cache.containsKey(s)) {
			return (ScpDownloader) ScpDownloader.cache.get(s);
		} else {
			ScpDownloader d = new ScpDownloader(host, user, passwd);
			
			Object r = null;
			if (ScpDownloader.cache.size() >= ScpDownloader.maxCache)
				r = ScpDownloader.cache.keySet().iterator().next();
			
			if (r != null) {
				ScpDownloader.cache.remove(r);
				System.out.println("ScpDownloader: Removed cache of " + r);
			}
			
			ScpDownloader.cache.put(s, d);
			return d;
		}
	}
	
	private static int checkAck(InputStream in) throws IOException{
		int b = in.read();
		// b may be 0 for success,
		//          1 for error,
		//          2 for fatal error,
		//          -1
		if (b == 0) return b;
		if (b == -1) return b;
		
		if (b == 1 || b == 2) {
			StringBuffer sb = new StringBuffer();
			
			int c;
			
			do {
				c = in.read();
				sb.append((char)c);
			} while(c != '\n');
			
			if(b == 1) System.out.println("ScpDownloader (ERROR): " + sb);
			if(b == 2) System.out.println("ScpDownloader (FATAL): " + sb);
		}
		
		return b;
	}
	
	public String getPassphrase() { return null; }
	public String getPassword() { return this.passwd; }
	
	public boolean promptPassword(String message) {
		System.out.println("ScpDownloader: " + message);
		return (this.passwd != null);
	}
	
	public boolean promptPassphrase(String message) {
		System.out.println("ScpDownloader: " + message);
		return true;
	}
	
	public boolean promptYesNo(String message) {
		System.out.println("ScpDownloader: " + message);
		return true;
	}
	
	public void showMessage(String message) { System.out.println("ScpDownloader: " + message); }
	
	protected void finalize() {
		System.out.println("ScpDownloader: Disposing " + this);
		this.dispose();
	}
}
