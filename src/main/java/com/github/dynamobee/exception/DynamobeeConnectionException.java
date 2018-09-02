package com.github.dynamobee.exception;

/**
 * Error while connection to DynamoDB
 */
public class DynamobeeConnectionException extends DynamobeeException {
	public DynamobeeConnectionException(String message, Exception baseException) {
		super(message, baseException);
	}
}
