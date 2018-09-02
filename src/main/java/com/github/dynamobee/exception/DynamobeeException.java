package com.github.dynamobee.exception;

public class DynamobeeException extends Exception {
	public DynamobeeException(String message) {
		super(message);
	}

	public DynamobeeException(String message, Throwable cause) {
		super(message, cause);
	}
}
