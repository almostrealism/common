package org.almostrealism.event;

import java.util.List;
import java.util.stream.Stream;

public class DefaultErrorEvent extends DefaultEvent {
	private String exceptionType;
	private String exceptionMessage;
	private List<String> stackTrace;

	public DefaultErrorEvent(String name) {
		this(name, 0);
	}

	public DefaultErrorEvent(String name, long duration) {
		super(name, duration);
	}

	public String getExceptionType() {
		return exceptionType;
	}

	public void setExceptionType(String exceptionType) {
		this.exceptionType = exceptionType;
	}

	public String getExceptionMessage() {
		return exceptionMessage;
	}

	public void setExceptionMessage(String exceptionMessage) {
		this.exceptionMessage = exceptionMessage;
	}

	public List<String> getStackTrace() {
		return stackTrace;
	}

	public void setStackTrace(List<String> stackTrace) {
		this.stackTrace = stackTrace;
	}

	
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
