/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.rcon;

/**
 * Base exception class for RCON protocol errors.
 *
 * @author Toshiaki Maki
 */
public class RconException extends RuntimeException {

	/**
	 * Constructs a new RCON exception with the specified message.
	 * @param message the detail message
	 */
	public RconException(String message) {
		super(message);
	}

	/**
	 * Constructs a new RCON exception with the specified message and cause.
	 * @param message the detail message
	 * @param cause the cause of the exception
	 */
	public RconException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Exception thrown when RCON authentication fails.
	 */
	public static class AuthFailedException extends RconException {

		public AuthFailedException() {
			super("rcon: authentication failed");
		}

	}

	/**
	 * Exception thrown when an invalid response type is received during authentication.
	 */
	public static class InvalidAuthResponseException extends RconException {

		public InvalidAuthResponseException() {
			super("rcon: invalid response type during auth");
		}

	}

	/**
	 * Exception thrown when the response format is unexpected.
	 */
	public static class UnexpectedFormatException extends RconException {

		public UnexpectedFormatException() {
			super("rcon: unexpected response format");
		}

	}

	/**
	 * Exception thrown when a command exceeds the maximum allowed length.
	 */
	public static class CommandTooLongException extends RconException {

		public CommandTooLongException() {
			super("rcon: command too long");
		}

	}

	/**
	 * Exception thrown when a response exceeds the maximum allowed length.
	 */
	public static class ResponseTooLongException extends RconException {

		public ResponseTooLongException() {
			super("rcon: response too long");
		}

	}

	/**
	 * Exception thrown when the connection is closed unexpectedly.
	 */
	public static class ConnectionClosedException extends RconException {

		public ConnectionClosedException() {
			super("rcon: connection closed");
		}

	}

}
