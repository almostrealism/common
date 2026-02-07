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

package io.flowtree.cli;

import io.flowtree.msg.Message;
import io.flowtree.node.Client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * TODO  Rewrite as a Jersey service.
 * 
 * @author  Michael Murray
 */
public class HttpCommandServer implements Runnable {
	private static final SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
	
	private final ServerSocket socket;
	private InputStream in;
	private OutputStream out;
	private BufferedReader br;
	private PrintStream ps;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException {
		ServerSocket socket = new ServerSocket(Integer.parseInt(args[0]));
		HttpCommandServer s = new HttpCommandServer(socket);
		
		Client c = Client.getCurrentClient();
		ThreadGroup g = null;
		if (c != null) g = c.getServer().getThreadGroup();
		Thread t = new Thread(g, s, "HttpCommandServer");
		t.start();
		System.out.println("HttpCommandServer: Started");
	}
	
	public HttpCommandServer(ServerSocket socket) {
		this.socket = socket;
	}
	
	public void run() {
		while (true) {
			try (Socket connection = this.socket.accept()) {
				System.out.println("HttpCommandServer: Accepted connection...");
				
				this.in = connection.getInputStream();
				this.out = connection.getOutputStream();
				System.out.println("HttpCommandServer: Got IO streams...");
				
				this.br = new BufferedReader(new InputStreamReader(this.in));
				this.ps = new PrintStream(this.out, false, StandardCharsets.US_ASCII);
				System.out.println("HttpCommandServer: Constructed input buffer and print stream...");
				
				String command = null;
				
				w: while(true) {
					String s = this.br.readLine();
					
					s = s.trim();
					
					if (s == null || s.equals("")) {
						break;
					} else if (s.startsWith("GET /")) {
						int p = s.indexOf("?") + 1;
						int q = s.indexOf(" HTTP/");
						
						command = s.substring(p, q);
						command = command.replaceAll("%20", " ");
					}
				}
				
				if (Message.verbose)
					System.out.println("HttpCommandServer: Received " + command);
				
				if (command != null) {
					String date = HttpCommandServer.format.format(new Date());
					
					this.ps.println("HTTP/1.0 200 OK");
					this.ps.println("Date: " + date);
					this.ps.println("Content-Type: text/html");
					this.ps.println("Connection: Close");
					this.ps.println();
					this.ps.println(FlowTreeCliServer.runCommand(command, ps));
					
					if (Message.verbose)
						System.out.println("HttpCommandServer: Sent HTTP header and output.");
				} else {
					System.out.println("HttpCommandServer: Received null command");
				}
				
				this.in.close();
				this.out.close();
				connection.close();
			} catch (IOException ioe) {
				System.out.println("HttpCommandServer: IO error accepting connection (" +
									ioe.getMessage() + ")");
			} catch (Exception e) {
				System.out.println("HttpCommandServer: " + e);
			}
		}
	}
}
