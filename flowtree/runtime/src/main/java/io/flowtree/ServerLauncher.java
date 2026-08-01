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

package io.flowtree;

import io.flowtree.airflow.AirflowJobFactory;
import io.flowtree.job.JobFactory;
import org.almostrealism.io.Console;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

/**
 * Entry-point helper that parses command-line arguments, constructs a
 * {@link Server}, and keeps the JVM alive until the server terminates.
 *
 * <p>This class owns the logic that was previously inlined in
 * {@link Server#main(String[])} so that the main class body stays below the
 * 1 500-line limit enforced by the project's Checkstyle configuration.
 *
 * <p>Usage:
 * <pre>
 *   java io.flowtree.Server [properties-file [job-factory-class | -p]]
 * </pre>
 * Pass {@code -p} as the second argument for passive mode (no {@link JobFactory}).
 */
class ServerLauncher {

    /**
     * Private constructor — this class is not meant to be instantiated.
     */
    private ServerLauncher() { }

    /**
     * Parses {@code args}, builds and starts a {@link Server}, then blocks the
     * calling thread indefinitely so that the daemon server threads remain alive.
     *
     * <p>The full class name of the {@link JobFactory} to use can be replaced
     * with {@code -p} to indicate that the server should operate in passive mode.
     *
     * @param args  {@code {path to properties file, full classname for JobFactory}}.
     *              Both arguments are optional.
     */
    static void launch(String[] args) {
        Properties p = new Properties();

        if (args.length > 0) {
            try {
                p.load(new FileInputStream(args[0]));
            } catch (FileNotFoundException fnf) {
                Console.root().println("Server: Properties file not found.");
                System.exit(1);
            } catch (IOException ioe) {
                Console.root().println("Server: IO error loading properties file.");
                System.exit(2);
            }
        }

        JobFactory j = null;

        if (args.length < 2) {
            j = new AirflowJobFactory();
        } else if (!args[1].equals("-p")) {
            try {
                j = (JobFactory) Class.forName(args[1]).newInstance();
            } catch (InstantiationException ie) {
                Console.root().warn("Server: " + ie);
                System.exit(3);
            } catch (IllegalAccessException ia) {
                Console.root().warn("Server: " + ia);
                System.exit(4);
            } catch (ClassNotFoundException cnf) {
                Console.root().warn("Server: " + cnf);
                System.exit(5);
            } catch (ClassCastException cc) {
                Console.root().warn("Server: " + cc);
                System.exit(6);
            }
        }

        try {
            Server s = new Server(p, j);
            s.start();

            // Keep the JVM alive (all server threads are daemon).
            Thread.currentThread().join();
        } catch (IOException ioe) {
            Console.root().warn("Server: " + ioe);
            System.exit(7);
        } catch (InterruptedException ie) {
            Console.root().println("Server: Interrupted");
        }
    }
}
