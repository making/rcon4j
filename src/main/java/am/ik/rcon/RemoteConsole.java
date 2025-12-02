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

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A client for the Source RCON (Remote Console) protocol.
 *
 * <p>
 * RCON is a TCP/IP-based protocol that allows remote administration of game servers,
 * commonly used by Minecraft, Valve Source Engine games, and others.
 * </p>
 *
 * <p>
 * This implementation uses {@link SocketChannel} which is compatible with Java Virtual
 * Threads (Project Loom). When running on Virtual Threads, I/O operations will properly
 * unmount the virtual thread instead of blocking the carrier thread.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * try (RemoteConsole rcon = RemoteConsole.connect("localhost:25575", "password")) {
 *     RconResponse response = rcon.command("list");
 *     System.out.println(response.body());
 * }
 * }</pre>
 *
 * <p>
 * With custom timeouts using builder:
 * </p>
 *
 * <pre>{@code
 * try (RemoteConsole rcon = RemoteConsole.builder("localhost:25575", "password")
 *         .connectTimeout(Duration.ofSeconds(30))
 *         .readTimeout(Duration.ofSeconds(60))
 *         .connect()) {
 *     RconResponse response = rcon.command("list");
 *     System.out.println(response.body());
 * }
 * }</pre>
 *
 * @author Toshiaki Maki
 * @see <a href="https://developer.valvesoftware.com/wiki/Source_RCON_Protocol">Source
 * RCON Protocol</a>
 */
public class RemoteConsole implements Closeable {

	private static final int CMD_AUTH = 3;

	private static final int CMD_EXEC_COMMAND = 2;

	private static final int RESP_RESPONSE = 0;

	private static final int RESP_AUTH_RESPONSE = 2;

	private static final int READ_BUFFER_SIZE = 4110;

	private static final int MAX_COMMAND_LENGTH = 1014;

	private static final int MAX_DATA_SIZE = 4106;

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(2);

	private final SocketChannel channel;

	private final AtomicInteger requestId;

	private final Duration readTimeout;

	private RemoteConsole(SocketChannel channel, Duration readTimeout) {
		this.channel = channel;
		this.requestId = new AtomicInteger(0x7fffffff);
		this.readTimeout = readTimeout;
	}

	/**
	 * Creates a new builder for RemoteConsole with the required parameters.
	 * @param host the host address in "host:port" format (e.g., "localhost:25575")
	 * @param password the RCON password
	 * @return a new Builder instance
	 */
	public static Builder builder(String host, String password) {
		return new Builder(host, password);
	}

	/**
	 * Connects to an RCON server and authenticates with the given password. This is a
	 * shortcut for {@code RemoteConsole.builder(host, password).connect()}.
	 * @param host the host address in "host:port" format (e.g., "localhost:25575")
	 * @param password the RCON password
	 * @return a connected and authenticated RemoteConsole instance
	 * @throws UncheckedIOException if the connection fails
	 * @throws RconException.AuthFailedException if authentication fails
	 * @throws RconException.InvalidAuthResponseException if the server returns an invalid
	 * auth response
	 */
	public static RemoteConsole connect(String host, String password) {
		return builder(host, password).connect();
	}

	/**
	 * Sends a command to the RCON server and reads the response.
	 * @param cmd the command to execute
	 * @return the response from the server
	 * @throws UncheckedIOException if an I/O error occurs
	 * @throws RconException.CommandTooLongException if the command exceeds the maximum
	 * length
	 */
	public RconResponse command(String cmd) {
		write(cmd);
		return read();
	}

	/**
	 * Sends a command to the RCON server.
	 * @param cmd the command to execute
	 * @return the request ID assigned to this command
	 * @throws UncheckedIOException if an I/O error occurs
	 * @throws RconException.CommandTooLongException if the command exceeds the maximum
	 * length
	 */
	public int write(String cmd) {
		return writeCommand(CMD_EXEC_COMMAND, cmd);
	}

	/**
	 * Reads a response from the RCON server using the configured read timeout.
	 * @return the response containing the body and request ID
	 * @throws UncheckedIOException if an I/O error occurs
	 */
	public RconResponse read() {
		return read(this.readTimeout);
	}

	/**
	 * Reads a response from the RCON server with a custom timeout.
	 * @param timeout the read timeout
	 * @return the response containing the body and request ID
	 * @throws UncheckedIOException if an I/O error occurs
	 */
	public RconResponse read(Duration timeout) {
		RawResponse raw = readResponse(timeout);
		if (raw.responseType() != RESP_RESPONSE) {
			return new RconResponse("", 0);
		}
		return new RconResponse(new String(raw.data(), StandardCharsets.UTF_8), raw.requestId());
	}

	/**
	 * Returns the local address of this connection.
	 * @return the local socket address
	 */
	public SocketAddress localAddress() {
		try {
			return this.channel.getLocalAddress();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns the remote address of this connection.
	 * @return the remote socket address
	 */
	public SocketAddress remoteAddress() {
		try {
			return this.channel.getRemoteAddress();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public void close() {
		try {
			this.channel.close();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void authenticate(String password, Duration timeout) {
		int authRequestId = writeCommand(CMD_AUTH, password);

		RawResponse response = readResponse(timeout);

		if (response.responseType() != RESP_AUTH_RESPONSE) {
			response = readResponse(timeout);
		}

		if (response.responseType() != RESP_AUTH_RESPONSE) {
			throw new RconException.InvalidAuthResponseException();
		}

		if (response.requestId() != authRequestId) {
			throw new RconException.AuthFailedException();
		}
	}

	private int writeCommand(int cmdType, String str) {
		byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
		if (strBytes.length > MAX_COMMAND_LENGTH) {
			throw new RconException.CommandTooLongException();
		}

		int reqId = nextRequestId();

		int packetSize = 10 + strBytes.length;
		ByteBuffer buffer = ByteBuffer.allocate(4 + packetSize);
		// Set byte order to little-endian as required by RCON protocol
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(packetSize);
		buffer.putInt(reqId);
		buffer.putInt(cmdType);
		buffer.put(strBytes);
		buffer.put((byte) 0);
		buffer.put((byte) 0);
		buffer.flip();

		try {
			while (buffer.hasRemaining()) {
				this.channel.write(buffer);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}

		return reqId;
	}

	private int nextRequestId() {
		return this.requestId.updateAndGet(id -> {
			if ((id & 0x0fffffff) != id) {
				return (int) ((System.nanoTime() / 100000) % 100000);
			}
			return id + 1;
		});
	}

	private RawResponse readResponse(Duration timeout) {
		try {
			this.channel.socket().setSoTimeout((int) timeout.toMillis());

			// Allocate buffer in write mode (position=0, limit=capacity)
			ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
			// Set byte order to little-endian as required by RCON protocol
			readBuffer.order(ByteOrder.LITTLE_ENDIAN);

			// Read at least 4 bytes (packet size field) into buffer
			int bytesRead = readAtLeast(readBuffer, 4);
			if (bytesRead < 4) {
				throw new RconException.ConnectionClosedException();
			}

			// Switch to read mode: set limit to current position, then reset position to
			// 0
			readBuffer.flip();
			// Read the packet size (first 4 bytes)
			int dataSize = readBuffer.getInt();

			if (dataSize < 10) {
				throw new RconException.UnexpectedFormatException();
			}

			if (dataSize > MAX_DATA_SIZE) {
				throw new RconException.ResponseTooLongException();
			}

			// Switch back to write mode: copy unread data to beginning, position after
			// copied data
			readBuffer.compact();

			// Read remaining packet data if needed
			int remaining = dataSize - readBuffer.position();
			if (remaining > 0) {
				int additionalRead = readAtLeast(readBuffer, readBuffer.position() + remaining) - readBuffer.position();
				if (additionalRead < remaining) {
					throw new RconException.ConnectionClosedException();
				}
			}

			// Switch to read mode for parsing
			readBuffer.flip();
			return parseResponseData(readBuffer, dataSize);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private int readAtLeast(ByteBuffer buffer, int minBytes) {
		try {
			while (buffer.position() < minBytes) {
				int read = this.channel.read(buffer);
				if (read < 0) {
					return buffer.position();
				}
			}
			return buffer.position();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static RawResponse parseResponseData(ByteBuffer buffer, int length) {
		if (length < 10) {
			throw new RconException.UnexpectedFormatException();
		}

		// Read request ID (4 bytes) and response type (4 bytes) from buffer
		int requestId = buffer.getInt();
		int responseType = buffer.getInt();

		// Read body bytes (length minus 8 bytes for IDs and 2 bytes for null terminators)
		int bodyLength = length - 10;
		byte[] body = new byte[bodyLength];
		buffer.get(body);

		// Find null terminator to get actual string length
		int nullIndex = 0;
		for (int i = 0; i < body.length; i++) {
			if (body[i] == 0) {
				nullIndex = i;
				break;
			}
			nullIndex = i + 1;
		}

		// Trim body to exclude null terminators
		byte[] trimmedBody = new byte[nullIndex];
		System.arraycopy(body, 0, trimmedBody, 0, nullIndex);

		return new RawResponse(responseType, requestId, trimmedBody);
	}

	private static SocketAddress parseHostPort(String host) {
		int colonIndex = host.lastIndexOf(':');
		if (colonIndex < 0) {
			throw new IllegalArgumentException("Invalid host format. Expected 'host:port', got: " + host);
		}
		String hostname = host.substring(0, colonIndex);
		int port;
		try {
			port = Integer.parseInt(host.substring(colonIndex + 1));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Invalid port number in host: " + host, ex);
		}
		return new InetSocketAddress(hostname, port);
	}

	private record RawResponse(int responseType, int requestId, byte[] data) {
	}

	/**
	 * Builder for creating {@link RemoteConsole} instances with custom configuration.
	 */
	public static class Builder {

		private final String host;

		private final String password;

		private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

		private Duration readTimeout = DEFAULT_READ_TIMEOUT;

		private Builder(String host, String password) {
			this.host = Objects.requireNonNull(host, "host must not be null");
			this.password = Objects.requireNonNull(password, "password must not be null");
		}

		/**
		 * Sets the connection timeout.
		 * @param connectTimeout the connection timeout (default: 10 seconds)
		 * @return this builder
		 */
		public Builder connectTimeout(Duration connectTimeout) {
			this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
			return this;
		}

		/**
		 * Sets the read timeout for responses.
		 * @param readTimeout the read timeout (default: 2 minutes)
		 * @return this builder
		 */
		public Builder readTimeout(Duration readTimeout) {
			this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout must not be null");
			return this;
		}

		/**
		 * Connects to the RCON server and authenticates.
		 * @return a connected and authenticated RemoteConsole instance
		 * @throws UncheckedIOException if the connection fails
		 * @throws RconException.AuthFailedException if authentication fails
		 * @throws RconException.InvalidAuthResponseException if the server returns an
		 * invalid auth response
		 */
		public RemoteConsole connect() {
			SocketAddress address = parseHostPort(this.host);
			SocketChannel channel = null;
			try {
				channel = SocketChannel.open();
				channel.socket().connect(address, (int) this.connectTimeout.toMillis());
				RemoteConsole console = new RemoteConsole(channel, this.readTimeout);
				console.authenticate(this.password, this.connectTimeout);
				return console;
			}
			catch (IOException ex) {
				if (channel != null) {
					try {
						channel.close();
					}
					catch (IOException closeEx) {
						ex.addSuppressed(closeEx);
					}
				}
				throw new UncheckedIOException(ex);
			}
		}

	}

}
