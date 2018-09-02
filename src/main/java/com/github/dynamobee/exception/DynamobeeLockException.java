package com.github.dynamobee.exception;

/**
 * Error while can not obtain process lock
 */
public class DynamobeeLockException extends DynamobeeException {
  public DynamobeeLockException(String message) {
    super(message);
  }
}
