package io.flowtree.job;

import org.almostrealism.io.JobOutput;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * {@link Output} provides a way for the results of {@link Job}s
 * to be sent to an interested party.
 *
 * @author  Michael Murray
 */
public class Output implements Function<JobOutput, Boolean> {
	public static boolean verbose = false;

	private final String outputHost;
	private final int outputPort;

	public Output(String outputHost, int outputPort) {
		this.outputHost = outputHost;
		this.outputPort = outputPort;
	}

	/**
	 * Sends the specified {@link JobOutput} to the output server.
	 *
	 * @param o  Output to send.
	 * @return  True if send is successful, false otherwise.
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
					System.out.println("Output: Opened socket " + s);

				if (verbose)
					System.out.println("Output: Writing " + o + "...");
				out.writeUTF(o.getClass().getName());
				o.writeExternal(out);

//				out.writeObject(o);

				done = true;

				return true;
			} catch (ConnectException ce) {
				if (i >= 4) {
					System.out.println("Output: Error connection to output host - giving up");
					return false;
				} else {
					System.out.println("Output: Error connecting to output host - retry in " + sleep + " sec.");
					try { Thread.sleep(sleep * 1000); } catch (InterruptedException ie) {}
				}
			} catch (UnknownHostException uh) {
				System.out.println("Output: Output host (" + this.outputHost + ":"+ this.outputPort + ") not found.");
				return false;
			} catch (IOException ioe) {
				System.out.println("Output: " + ioe);
				ioe.printStackTrace(System.out);
				return false;
			} catch (Exception e) {
				if (done)
					System.out.println("Client.writeOutput: " + e);
				else
					System.out.println("Client.writeOutput: Ended prematurely due to " + e);
			}
		}

		return done;
	}
}
