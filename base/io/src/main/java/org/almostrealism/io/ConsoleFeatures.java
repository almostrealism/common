/*
 * Copyright 2023 Michael Murray
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

import java.util.function.UnaryOperator;

/**
 * Provides convenient logging methods that automatically format messages with class names.
 *
 * <p>This interface is intended to be implemented by classes that need logging capabilities.
 * It provides default methods that delegate to a {@link Console} instance, automatically
 * prefixing messages with the implementing class's simple name.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyProcessor implements ConsoleFeatures {
 *     public void process() {
 *         log("Starting processing");  // Output: "[HH:mm.ss] MyProcessor: Starting processing"
 *         warn("Low memory");          // Output: "[HH:mm.ss] WARN: MyProcessor: Low memory"
 *     }
 * }
 * }</pre>
 *
 * <h2>Custom Console</h2>
 * <pre>{@code
 * public class MyClass implements ConsoleFeatures {
 *     private Console myConsole = Console.root().child();
 *
 *     @Override
 *     public Console console() {
 *         return myConsole;  // Use custom console instead of root
 *     }
 * }
 * }</pre>
 *
 * @see Console
 * @see OutputFeatures
 */
public interface ConsoleFeatures {
	/**
	 * Returns the class to use for log message prefixes.
	 * Override to customize the class name shown in logs.
	 *
	 * @return the class for log formatting
	 */
	default Class getLogClass() {
		return getClass();
	}

	/**
	 * Formats a message by prefixing it with the log class's simple name.
	 *
	 * @param msg the message to format
	 * @return the formatted message
	 */
	default String formatMessage(String msg) {
		return getLogClass().getSimpleName() + ": " + msg;
	}

	/**
	 * Logs a value by converting it to a string.
	 *
	 * @param <T> the value type
	 * @param value the value to log
	 */
	default <T> void log(T value) {
		log(String.valueOf(value));
	}

	/**
	 * Logs a message with automatic class name prefix.
	 *
	 * @param message the message to log
	 */
	default void log(String message) {
		console().println(formatMessage(message));
	}

	/**
	 * Logs a warning message with automatic class name prefix.
	 *
	 * @param message the warning message
	 */
	default void warn(String message) {
		console().warn(formatMessage(message), null);
	}

	/**
	 * Logs a warning for an exception, using the exception's message.
	 *
	 * @param ex the exception
	 */
	default void warn(Throwable ex) {
		warn(ex.getMessage(), ex);
	}

	/**
	 * Logs a warning message with an associated exception.
	 *
	 * @param message the warning message
	 * @param ex the exception
	 */
	default void warn(String message, Throwable ex) {
		console().warn(formatMessage(message), ex);
	}

	/**
	 * Sends an informational alert.
	 *
	 * @param message the alert message
	 */
	default void alert(String message) {
		console().alert(Alert.Severity.INFO, message);
	}

	/**
	 * Sends an error alert for an exception and also logs a warning.
	 *
	 * @param message the alert message
	 * @param ex the exception
	 */
	default void alert(String message, Throwable ex) {
		console().alert(message, ex);
		warn(message, ex);
	}

	/**
	 * Returns the console instance to use for logging.
	 * Override to use a custom console instead of the root console.
	 *
	 * @return the console instance
	 */
	default Console console() {
		return Console.root();
	}

	/**
	 * Creates a filter that suppresses duplicate messages within a time interval.
	 * Useful for preventing log spam from repeated messages.
	 *
	 * @param interval the minimum time interval between duplicate messages, in milliseconds
	 * @return a filter that suppresses duplicates
	 */
	static UnaryOperator<String> duplicateFilter(long interval) {
		return new UnaryOperator<>() {
			private String lastMessage = null;
			private long lastTime = 0;

			@Override
			public String apply(String message) {
				long now = System.currentTimeMillis();
				long diff = now - lastTime;
				if (diff < interval && message.equals(lastMessage)) {
					return null;
				}

				lastMessage = message;
				lastTime = now;
				return message;
			}
		};
	}
}
