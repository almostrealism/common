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

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import org.almostrealism.io.Console;

/**
 * Utility class that reflectively instantiates {@link Job} objects from encoded
 * data strings.  The encoded format used throughout FlowTree places the fully
 * qualified class name first, followed by a series of key–value pairs separated
 * by {@link JobFactory#ENTRY_SEPARATOR}.
 *
 * <p>This class has no instance state and exposes only a single static method.
 * It exists solely to keep this parsing logic out of {@link Server}.
 */
class JobClassLoader {

    /**
     * Private constructor — this class is not meant to be instantiated.
     */
    private JobClassLoader() { }

    /**
     * Constructs a class of the type specified by full name in the first term
     * of the specified data string and sets other properties accordingly.
     *
     * @param data  Encoded job data whose first token is the fully qualified class name.
     * @return  Instance of {@link Job} created, or {@code null} if instantiation fails.
     */
    static Job instantiateJobClass(String data) {
        int index = data.indexOf(JobFactory.ENTRY_SEPARATOR);
        String className = data.substring(0, index);

        Job j = null;

        try {
            j = (Job) Class.forName(className).newInstance();

            boolean end = false;

            while (!end) {
                data = data.substring(index + JobFactory.ENTRY_SEPARATOR.length());
                index = data.indexOf(JobFactory.ENTRY_SEPARATOR);

                while (data.charAt(index + JobFactory.ENTRY_SEPARATOR.length()) == '/'
                        || index > 0 && data.charAt(index - 1) == '\\') {
                    index = data.indexOf(JobFactory.ENTRY_SEPARATOR,
                            index + JobFactory.ENTRY_SEPARATOR.length());
                }

                String s = null;

                if (index <= 0) {
                    s = data;
                    end = true;
                } else {
                    s = data.substring(0, index);
                }

                int k = s.indexOf(JobFactory.KEY_VALUE_SEPARATOR);
                int len = JobFactory.KEY_VALUE_SEPARATOR.length();

                if (k > 0) {
                    String key = s.substring(0, k);
                    String value = s.substring(k + len);
                    j.set(key, value);
                } else {
                    String key = s;
                    String value = data.substring(index + len);
                    j.set(key, value);
                    end = true;
                }
            }
        } catch (Exception e) {
            Console.root().warn("Server: " + e);
        }

        return j;
    }
}
