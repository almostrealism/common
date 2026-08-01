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
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;

/**
 * Downloads files from a remote host via SCP using JSch, with connection retry and a
 * small LRU-style downloader cache keyed by host/user/password.
 *
 * <p>The downloader maintains a single SSH session per instance; concurrent downloads are
 * serialised via {@code synchronized} on the instance. Sessions are reconnected automatically
 * on failure up to the configured retry count.</p>
 */
public class ScpDownloader implements UserInfo, ConsoleFeatures {
	/** Maximum number of cached downloader instances. */
	private static final int maxCache = 5;
	/** Cache of live {@link ScpDownloader} instances keyed by "host|user|password". */
	private static LinkedHashMap cache;

	/** The JSch SSH library instance used to create sessions. */
	private final JSch sch;
	/** The active SSH session, or {@code null} if not connected. */
	private Session session;
	/** Remote host name or IP address. */
	private final String host;
	/** SSH user name. */
	private final String user;
	/** SSH password. */
	private final String passwd;

	/** Local directory to which downloaded files are written. */
	private File ddir;
	/** Number of connection/download attempts before giving up. */
	private int retry = 3;

	/** {@code true} while a download stream is active, used to serialize concurrent calls. */
	private boolean streamOpen = false;

	/**
	 * Constructs a new {@link ScpDownloader} connected to the given host.
	 *
	 * <p>Retries connection up to {@link #retry} times before throwing.</p>
	 *
	 * @param host   The remote host name or IP
	 * @param user   The SSH user name
	 * @param passwd The SSH password
	 * @throws IOException If a connection cannot be established after all retries
	 */
	public ScpDownloader(String host, String user, String passwd) throws IOException {
		this.host = host;
		this.user = user;
		this.passwd = passwd;
		
		this.sch = new JSch();
		
		i: for (int i = 0; i < this.retry; i++) {
			if (i > 0) log("ScpDownloader: Retrying...");

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
	
	/**
	 * Sets the local directory to which downloaded files are written.
	 *
	 * @param dir The download directory path
	 */
	public void setDownloadDir(String dir) { this.ddir = new File(dir); }

	/**
	 * Sets the number of connection/download retry attempts.
	 *
	 * @param retry The maximum number of attempts
	 */
	public void setRetry(int retry) { this.retry = retry; }

	/**
	 * Establishes a new SSH session to the remote host.
	 *
	 * @throws IOException If the JSch session cannot be opened
	 */
	public void init() throws IOException {
		try {
			this.session = this.sch.getSession(this.user, this.host, 22);
			this.session.setUserInfo(this);
			this.session.connect();
		} catch (JSchException e) {
			log("ScpDownloader (INIT): " + e.getMessage());
			throw new IOException("JSCP Error -- " + e.getMessage());
		}
	}
	
	/**
	 * Disconnects the current SSH session and sets it to {@code null}.
	 */
	public void dispose() {
		if (this.session != null) {
			this.session.disconnect();
			this.session = null;
		}
	}
	
	/**
	 * Downloads the remote file at the given path, writing its contents to the provided
	 * output stream.  Blocks if another download is currently in progress.
	 *
	 * @param file The remote file path to download
	 * @param fout The output stream to write the downloaded data to
	 * @throws IOException If the download fails after all retries
	 */
	public synchronized void download(String file, OutputStream fout) throws IOException {
		while (this.streamOpen) {
			try {
				this.wait();
			} catch (InterruptedException ie) { return; }
		}
		
		ScpDownloader.this.streamOpen = true;
		
		i: for (int i = 0; i < ScpDownloader.this.retry; i++) {
			if (i > 0) log("ScpDownloader: Retrying...");

			boolean done = false;

			try {
//				TODO  This class is missing
//				ScpFromMessage scp = new ScpFromMessage(this.session, file, fout, true);
//				scp.execute();

				done = true;
				log("ScpDownloader: Done");
				fout.flush();
				break;
			} catch (IOException e) {
				log("ScpDownloader: " + e.getMessage());
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
	
	/**
	 * Downloads the remote file at the given path to a temporary local file in the download
	 * directory and returns its canonical path.
	 *
	 * @param file The remote file path to download
	 * @return The canonical local file path of the downloaded file
	 * @throws IOException If the download fails
	 */
	public String download(String file) throws IOException {
		String orig = file;

		int index = file.lastIndexOf("/");

		if (index > 0) {
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
	
	/**
	 * Returns a cached {@link ScpDownloader} for the given host/user/password combination,
	 * creating and caching a new one if no matching entry exists.
	 *
	 * <p>The cache holds at most {@link #maxCache} downloaders; if the limit is reached, an
	 * arbitrary existing entry is evicted before the new one is added.</p>
	 *
	 * @param host   The remote host name or IP
	 * @param user   The SSH user name
	 * @param passwd The SSH password
	 * @return A connected {@link ScpDownloader} for the given credentials
	 * @throws IOException If a new connection cannot be established
	 */
	public static synchronized ScpDownloader getDownloader(String host, String user, String passwd) throws IOException {
		if (ScpDownloader.cache == null) ScpDownloader.cache = new LinkedHashMap();
		
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
				Console.root().println("ScpDownloader: Removed cache of " + r);
			}
			
			ScpDownloader.cache.put(s, d);
			return d;
		}
	}
	
	/**
	 * Reads the SCP acknowledgement byte from the input stream.
	 *
	 * @param in The input stream to read from
	 * @return 0 on success, 1 on error, 2 on fatal error, -1 on stream end
	 * @throws IOException If reading from the stream fails
	 */
	private static int checkAck(InputStream in) throws IOException{
		int b = in.read();
		// b may be 0 for success,
		//          1 for error,
		//          2 for fatal error,
		//          -1
		if (b == 0) return b;
		if (b == -1) return b;
		
		if (b == 1 || b == 2) {
			StringBuilder sb = new StringBuilder();
			
			int c;
			
			do {
				c = in.read();
				sb.append((char)c);
			} while(c != '\n');
			
			if(b == 1) Console.root().println("ScpDownloader (ERROR): " + sb);
			if(b == 2) Console.root().println("ScpDownloader (FATAL): " + sb);
		}
		
		return b;
	}
	
	/** {@inheritDoc} Always returns {@code null}; passphrases are not used. */
	@Override
	public String getPassphrase() { return null; }

	/** {@inheritDoc} Returns the SSH password provided at construction time. */
	@Override
	public String getPassword() { return this.passwd; }

	/** {@inheritDoc} */
	@Override
	public boolean promptPassword(String message) {
		log("ScpDownloader: " + message);
		return (this.passwd != null);
	}

	/** {@inheritDoc} Always returns {@code true}. */
	@Override
	public boolean promptPassphrase(String message) {
		log("ScpDownloader: " + message);
		return true;
	}

	/** {@inheritDoc} Always returns {@code true}. */
	@Override
	public boolean promptYesNo(String message) {
		log("ScpDownloader: " + message);
		return true;
	}

	/** {@inheritDoc} Logs the message to standard output. */
	@Override
	public void showMessage(String message) { log("ScpDownloader: " + message); }

	/**
	 * Disposes the SSH session when this object is garbage collected.
	 */
	protected void finalize() {
		log("ScpDownloader: Disposing " + this);
		this.dispose();
	}
}
