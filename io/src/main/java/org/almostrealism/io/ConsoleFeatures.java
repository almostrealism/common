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

public interface ConsoleFeatures {
	default Class getLogClass() {
		return getClass();
	}

	default String formatMessage(String msg) {
		return getLogClass().getSimpleName() + ": " + msg;
	}

	default <T> void log(T value) {
		log(String.valueOf(value));
	}

	default void log(String message) {
		console().println(formatMessage(message));
	}

	default void warn(String message) {
		console().warn(formatMessage(message), null);
	}

	default void warn(String message, Throwable ex) {
		console().warn(formatMessage(message), ex);
	}

	default Console console() {
		return Console.root();
	}

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
