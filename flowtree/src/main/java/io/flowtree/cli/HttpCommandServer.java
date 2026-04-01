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
 * A minimal HTTP/1.0 server that accepts incoming connections and dispatches
 * URL-encoded FlowTree CLI commands.
 *
 * <p>Each accepted connection is processed synchronously on the server thread.
 * The server reads the first HTTP GET line, extracts the query string that
 * follows the {@code ?} delimiter, URL-decodes space characters, and passes
 * the resulting string to
 * {@link FlowTreeCliServer#runCommand(String, PrintStream)} for execution.
 * The command output is returned as an HTML response body with a
 * {@code 200 OK} status.
 *
 * <p>This class is intended to be rewritten as a Jersey JAX-RS service.
 *
 * @author  Michael Murray
 * @see FlowTreeCliServer
 */
// TODO  Rewrite as a Jersey service.
public class HttpCommandServer implements Runnable {

	/** Date formatter for the HTTP {@code Date:} response header. */
	private static final SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");

	/** Server socket on which this server listens for incoming HTTP connections. */
	private final ServerSocket socket;

	/** Input stream of the currently active connection. */
	private InputStream in;

	/** Output stream of the currently active connection. */
	private OutputStream out;

	/** Buffered reader wrapping the current connection's input stream. */
	private BufferedReader br;

	/** US-ASCII print stream wrapping the current connection's output stream. */
	private PrintStream ps;

	/**
	 * Starts a standalone {@link HttpCommandServer} on the port given by
	 * {@code args[0]}. If a {@link Client} is available the server thread is
	 * added to the server's {@link ThreadGroup}; otherwise it runs in the
	 * default thread group.
	 *
	 * @param args command-line arguments; {@code args[0]} must be a valid port
	 *             number string
	 * @throws IOException if the server socket cannot be created
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

	/**
	 * Constructs an {@link HttpCommandServer} that will accept connections on
	 * the given server socket.
	 *
	 * @param socket the {@link ServerSocket} to accept connections on
	 */
	public HttpCommandServer(ServerSocket socket) {
		this.socket = socket;
	}

	/**
	 * Runs the HTTP server accept loop. For each accepted connection the server:
	 * <ol>
	 *   <li>Reads lines until a blank line, extracting the command from the first
	 *       HTTP GET line's query string.</li>
	 *   <li>Invokes {@link FlowTreeCliServer#runCommand(String, PrintStream)} with
	 *       the decoded command.</li>
	 *   <li>Writes a complete HTTP/1.0 response with the command output as the
	 *       body and closes the connection.</li>
	 * </ol>
	 * Connections that produce a null command (e.g. no GET line was found) are
	 * logged but not responded to. IO and other errors are logged to standard
	 * output and the loop continues.
	 */
	@Override
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
