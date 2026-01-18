/*
 * Copyright 2024 Michael Murray
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

/**
 * Represents an alert message with a severity level.
 *
 * <p>Alerts are used to notify external systems or users of important events,
 * warnings, or errors. They can be delivered through custom
 * {@link AlertDeliveryProvider}s registered with a {@link Console}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Console console = Console.root();
 *
 * // Simple alerts
 * console.alert("Operation completed");  // INFO severity
 * console.alert(Alert.Severity.WARNING, "Low memory");
 *
 * // Alert from exception
 * try {
 *     riskyOperation();
 * } catch (Exception ex) {
 *     console.alert("Operation failed", ex);  // ERROR severity
 * }
 * }</pre>
 *
 * <h2>Custom Alert Delivery</h2>
 * <pre>{@code
 * console.addAlertDeliveryProvider(alert -> {
 *     if (alert.getSeverity() == Alert.Severity.ERROR) {
 *         sendEmailAlert(alert.getMessage());
 *     }
 * });
 * }</pre>
 *
 * @see Console#alert(Alert)
 * @see AlertDeliveryProvider
 */
public class Alert {
	private Severity severity;
	private String message;

	/**
	 * Creates an alert with the specified severity and message.
	 *
	 * @param severity the alert severity
	 * @param message the alert message
	 */
	public Alert(Severity severity, String message) {
		this.severity = severity;
		this.message = message;
	}

	/**
	 * Returns the alert severity.
	 *
	 * @return the severity
	 */
	public Severity getSeverity() {
		return severity;
	}

	/**
	 * Sets the alert severity.
	 *
	 * @param severity the severity
	 */
	public void setSeverity(Severity severity) {
		this.severity = severity;
	}

	/**
	 * Returns the alert message.
	 *
	 * @return the message
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Sets the alert message.
	 *
	 * @param message the message
	 */
	public void setMessage(String message) {
		this.message = message;
	}

	/**
	 * Alert severity levels.
	 */
	public enum Severity {
		/** Informational alert */
		INFO,
		/** Warning alert */
		WARNING,
		/** Error alert */
		ERROR
	}

	/**
	 * Creates an ERROR alert from an exception, using the exception's message.
	 *
	 * @param ex the exception
	 * @return an error alert
	 */
	public static Alert forThrowable(Throwable ex) {
		return forThrowable(null, ex);
	}

	/**
	 * Creates an ERROR alert from an exception with a custom message.
	 * The final message combines the custom message with the exception's message.
	 *
	 * @param message the custom message, or null to use only the exception message
	 * @param ex the exception
	 * @return an error alert
	 */
	public static Alert forThrowable(String message, Throwable ex) {
		String msg = ex.getMessage();
		if (msg == null) {
			msg = ex.getClass().getSimpleName();
		}

		return new Alert(Severity.ERROR, message == null ?
						msg : (message + " (" + msg + ")"));
	}
}
