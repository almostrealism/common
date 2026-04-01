package org.almostrealism.event;

import java.util.List;
import java.util.stream.Stream;

/**
 * An event that represents a Java exception, capturing the exception type,
 * message, and full stack trace.
 * <p>
 * Use the static factory method {@link #forException(Exception)} to create
 * an instance from a live exception.
 * </p>
 */
public class DefaultErrorEvent extends DefaultEvent {
	/** Fully-qualified class name of the exception (e.g., {@code java.lang.NullPointerException}). */
	private String exceptionType;

	/** Message from the exception's {@link Throwable#getMessage()} call. */
	private String exceptionMessage;

	/** Stack trace elements converted to strings, one entry per frame. */
	private List<String> stackTrace;

	/**
	 * Constructs an error event with the specified name and zero duration.
	 *
	 * @param name  the name of this error event
	 */
	public DefaultErrorEvent(String name) {
		this(name, 0);
	}

	/**
	 * Constructs an error event with the specified name and duration.
	 *
	 * @param name      the name of this error event
	 * @param duration  the duration of the activity that produced the error, in milliseconds
	 */
	public DefaultErrorEvent(String name, long duration) {
		super(name, duration);
	}

	/**
	 * Returns the fully-qualified class name of the exception.
	 *
	 * @return the exception type name
	 */
	public String getExceptionType() {
		return exceptionType;
	}

	/**
	 * Sets the fully-qualified class name of the exception.
	 *
	 * @param exceptionType  the exception type name
	 */
	public void setExceptionType(String exceptionType) {
		this.exceptionType = exceptionType;
	}

	/**
	 * Returns the message of the exception.
	 *
	 * @return the exception message
	 */
	public String getExceptionMessage() {
		return exceptionMessage;
	}

	/**
	 * Sets the exception message.
	 *
	 * @param exceptionMessage  the exception message
	 */
	public void setExceptionMessage(String exceptionMessage) {
		this.exceptionMessage = exceptionMessage;
	}

	/**
	 * Returns the stack trace as a list of strings, one per frame.
	 *
	 * @return the stack trace frames
	 */
	public List<String> getStackTrace() {
		return stackTrace;
	}

	/**
	 * Sets the stack trace.
	 *
	 * @param stackTrace  list of stack trace frame strings
	 */
	public void setStackTrace(List<String> stackTrace) {
		this.stackTrace = stackTrace;
	}

	/**
	 * Creates a {@code DefaultErrorEvent} from a live exception, capturing
	 * the exception's class name, message, and stack trace.
	 *
	 * @param e  the exception to capture
	 * @return   a new event populated from the exception
	 */
	public static DefaultErrorEvent forException(Exception e) {
		DefaultErrorEvent event = new DefaultErrorEvent(e.getClass().getName());
		event.exceptionType = e.getClass().getName();
		event.exceptionMessage = e.getMessage();
		event.stackTrace = Stream.of(e.getStackTrace())
				.map(StackTraceElement::toString)
				.toList();
		return event;
	}
}
