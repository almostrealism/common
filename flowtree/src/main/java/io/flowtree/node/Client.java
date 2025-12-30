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

package io.flowtree.node;

import io.almostrealism.db.Query;
import io.almostrealism.resource.Resource;
import io.flowtree.Server;
import io.flowtree.fs.OutputServer;
import io.flowtree.fs.ResourceDistributionTask;
import io.flowtree.ui.LoginDialog;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.Properties;

/**
 * A {@link Client} encapsulates a {@link Server} instance and keeps
 * track of login information.
 * 
 * @author  Michael Murray
 */
public class Client {
	private static Client client;
	
	private final String user;
	private final String passwd;
	private String outputHost;
	private int outputPort;
	
	private final Server server;
	
	/**
	 * Prompts for username and password and constructs a new
	 * Client instance.
	 * 
	 * @param args {URL of properties file for net.sf.j3d.network.server instance}
	 */
	public static void main(String[] args) {
		final Properties p = new Properties();
		
		try {
			InputStream in = (new URL(args[0])).openStream();
			p.load(in);            
		} catch (MalformedURLException e) {
			System.out.println("Client: Malformed properties URL");
			System.exit(1);
		} catch (IOException e) {
			System.out.println("Client: IO error loading properties");
			System.exit(2);
		}
		
		String user, passwd;
		
//		if (args.length >= 3) {
//			user = args[1];
//			passwd = args[2];
//		} else {
			final LoginDialog l = new LoginDialog();
			
			Runnable r = new Runnable() {
				public void run() {
					String user = l.getUser();
					String passwd = l.getPassword();

					try {
						Client.client = new Client(p, user, passwd, null);
					} catch (IOException e) {
						System.out.println("Client: " + e);
					}
				}
			};
			
			l.showDialog(r);
//		}
	}
	
	/**
	 * Constructs a new Client object. This constructor will
	 * start the {@link Server} thread.
	 * 
	 * @param p  Properties object to pass to Server.
	 * @param user  Username to use when sending job output.
	 * @param passwd  Password to use when sending job output.
	 * @param status  Label to display server status.
	 * @throws IOException  If an IOException occurs creating the Server instance.
	 */
	public Client(Properties p, String user, String passwd, JLabel status) throws IOException {
		this.user = user;
		this.passwd = passwd;
		
		this.outputHost = p.getProperty("servers.output.host", "localhost");
		this.outputPort = Integer.parseInt(p.getProperty("servers.output.port", "7788"));
		
		this.server = new Server(p, null);
		this.setStatusLabel(status);

		try {
			OutputServer s = new OutputServer(p, server);
			System.out.println("DB Server created");
		} catch (IOException ioe) {
			System.out.println("IO error starting DBS: " + ioe.getMessage());
		}

		this.server.start();
	}
	
	public String getUser() { return this.user; }
	
	public String getPassword() { return this.passwd; }
	
	public void setOutputHost(String host) { this.outputHost = host; }
	public void setOutputPort(int port) { this.outputPort = port; }
	public String getOutputHost() { return this.outputHost; }
	public int getOutputPort() { return this.outputPort; }
	
	public void setStatusLabel(JLabel label) { if (this.server != null) this.server.setStatusLabel(label); }
	
	public Server getServer() { return this.server; }
	
	/**
	 * Returns an OutputStream that can be used to write data to the specified uri on
	 * the distributed file system.
	 * 
	 * @param uri  URI to access.
	 * @return  An OutputStream to use.
	 * @throws IOException
	 */
	public OutputStream getOutputStream(String uri) throws IOException {
		return this.server.getOutputStream(uri);
	}
	
	/**
	 * Deletes the specified resource from the distributed database.
	 * 
	 * @param uri  Path to resource.
	 * @return  True if the resource was successfully deleted, false otherwise.
	 */
	public boolean deleteResource(String uri) {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		
		if (t == null)
			return false;
		else
			return t.deleteResource(uri);
	}
	
	/**
	 * Deletes the specified directory and recursively deletes the contents
	 * from the distributed database.
	 * 
	 * @param uri  Path to directory.
	 * @return  True if the directory and contents was successfully deleted, false otherwise.
	 */
	public boolean deleteDirectory(String uri) {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		
		if (t == null)
			return false;
		else
			return t.deleteDirectory(uri);
	}
	
	/**
	 * Attempts to load a resource from the specified URI using the current DistributedResourceTask.
	 * 
	 * @param uri  URI of resource to load.
	 * @return  Resource loaded.
	 * @throws IOException 
	 */
	public Resource loadResource(String uri) throws IOException {
		return this.server.loadResource(uri);
	}
	
	/**
	 * Sends the specified Query to the output server.
	 * 
	 * @param q  Query object to send.
	 * @return  The Hashtable resulting from the query or null if an error occurs.
	 */
	public Hashtable sendQuery(Query q) {
		try (Socket s = new Socket(this.outputHost, this.outputPort);
			ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
			ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
			
			out.writeUTF(q.getClass().getName());
			q.writeExternal(out);
			
			out.flush();
			
			Hashtable h = (Hashtable) in.readObject();
			return h;
		} catch (ClassNotFoundException cnf) {
			System.out.println("Client: " + cnf);
			return null;
		} catch (UnknownHostException uh) {
			System.out.println("Client: Output host " + this.outputHost + ":"+ this.outputPort + ") not found.");
			return null;
		} catch (IOException ioe) {
			System.out.println("Client: " + ioe);
			return null;
		}
	}
	
	/**
	 * Assigns the {@link Client} to be returned by the {@link #getCurrentClient()} method.
	 * 
	 * @param client  The Client instance to use.
	 */
	public static void setCurrentClient(Client client) { Client.client = client; }
	
	/**
	 * @return  The {@link Client} started by the {@link Client#main(String[])} method,
	 *          or otherwise assigned using {@link #setCurrentClient(Client)}.
	 */
	public static Client getCurrentClient() { return Client.client; }
}
