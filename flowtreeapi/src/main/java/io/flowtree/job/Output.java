package io.flowtree.job;

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.JobOutput;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * Sends {@link JobOutput} results from an executed {@link Job} to a remote output
 * server over a TCP socket connection.
 *
 * <p>{@link Output} implements {@link java.util.function.Function}{@code <JobOutput, Boolean>}
 * so it can be used directly as an output consumer on a {@link Job} via
 * {@link Job#setOutputConsumer(java.util.function.Consumer)}. Callers pass a
 * {@link JobOutput} instance and receive {@code true} if the result was delivered
 * successfully or {@code false} if all retry attempts were exhausted.</p>
 *
 * <h2>Retry Behaviour</h2>
 * <p>On a {@link java.net.ConnectException} the delivery is retried up to 4 times
 * with an exponential back-off starting at 3 seconds. Other I/O errors result in
 * an immediate failure with no retry.</p>
 *
 * @author  Michael Murray
 * @see JobOutput
 * @see Job#setOutputConsumer(java.util.function.Consumer)
 */
public class Output implements Function<JobOutput, Boolean>, ConsoleFeatures {
	/**
	 * When {@code true}, diagnostic messages about socket connections and write
	 * operations are printed to standard output. Defaults to {@code false}.
	 */
	public static boolean verbose = false;

	/** The hostname of the remote output server. */
	private final String outputHost;

	/** The TCP port of the remote output server. */
	private final int outputPort;

	/**
	 * Constructs an {@link Output} that will deliver results to the specified
	 * host and port.
	 *
	 * @param outputHost the hostname or IP address of the remote output server
	 * @param outputPort the TCP port on which the output server is listening
	 */
	public Output(String outputHost, int outputPort) {
		this.outputHost = outputHost;
		this.outputPort = outputPort;
	}

	/**
	 * Sends the specified {@link JobOutput} to the configured output server.
	 *
	 * <p>A new TCP connection is opened for each invocation. The output is
	 * serialized by writing the class name as a UTF string followed by the
	 * externalized form via {@link JobOutput#writeExternal(java.io.ObjectOutput)}.
	 * On a connection failure the delivery is retried up to 4 times; all other
	 * I/O failures cause an immediate return of {@code false}.</p>
	 *
	 * @param o the job output to deliver; must not be {@code null}
	 * @return {@code true} if the output was delivered successfully;
	 *         {@code false} if delivery failed after all retries
	 */
	@Override
	public Boolean apply(JobOutput o) {
		boolean done = false;
		int sleep = 3;

		for (int i = 0; !done; i++) {
			try (Socket s = new Socket(this.outputHost, this.outputPort);
				 ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
				 ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {

				if (verbose)
					log("Output: Opened socket " + s);

				if (verbose)
					log("Output: Writing " + o + "...");
				out.writeUTF(o.getClass().getName());
				o.writeExternal(out);

//				out.writeObject(o);

				done = true;

				return true;
			} catch (ConnectException ce) {
				if (i >= 4) {
					warn("Output: Error connection to output host - giving up");
					return false;
				} else {
					warn("Output: Error connecting to output host - retry in " + sleep + " sec.");
					try { Thread.sleep(sleep * 1000); } catch (InterruptedException ie) {}
				}
			} catch (UnknownHostException uh) {
				warn("Output: Output host (" + this.outputHost + ":"+ this.outputPort + ") not found.");
				return false;
			} catch (IOException ioe) {
				warn("Output: " + ioe.getMessage(), ioe);
				return false;
			} catch (Exception e) {
				if (done)
					warn("Client.writeOutput: " + e.getMessage(), e);
				else
					warn("Client.writeOutput: Ended prematurely due to " + e.getMessage(), e);
			}
		}

		return done;
	}
}
