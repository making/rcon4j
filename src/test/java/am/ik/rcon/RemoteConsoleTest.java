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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteConsoleTest {

	private static final int RESP_RESPONSE = 0;

	private static final int RESP_AUTH_RESPONSE = 2;

	@Test
	void connectAndAuthenticate() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			server.start();

			try (RemoteConsole rcon = RemoteConsole.builder("localhost:" + server.getPort(), "password")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()) {
				assertThat(rcon.localAddress()).isNotNull();
				assertThat(rcon.remoteAddress()).isNotNull();
			}
		}
	}

	@Test
	void authenticationFails() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			server.setAuthShouldFail(true);
			server.start();

			assertThatThrownBy(() -> RemoteConsole.builder("localhost:" + server.getPort(), "wrong")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()).isInstanceOf(RconException.AuthFailedException.class);
		}
	}

	@Test
	void executeCommand() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			server.setCommandResponse("There are 5 players online");
			server.start();

			try (RemoteConsole rcon = RemoteConsole.builder("localhost:" + server.getPort(), "password")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()) {
				RconResponse response = rcon.command("list");
				assertThat(response.body()).isEqualTo("There are 5 players online");
			}
		}
	}

	@Test
	void writeAndRead() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			server.setCommandResponse("Command executed");
			server.start();

			try (RemoteConsole rcon = RemoteConsole.builder("localhost:" + server.getPort(), "password")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()) {
				int requestId = rcon.write("help");
				RconResponse response = rcon.read(Duration.ofSeconds(5));
				assertThat(response.body()).isEqualTo("Command executed");
				assertThat(response.requestId()).isEqualTo(requestId);
			}
		}
	}

	@Test
	void commandTooLong() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			server.start();

			try (RemoteConsole rcon = RemoteConsole.builder("localhost:" + server.getPort(), "password")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()) {
				String longCommand = "a".repeat(2000);
				assertThatThrownBy(() -> rcon.write(longCommand))
					.isInstanceOf(RconException.CommandTooLongException.class);
			}
		}
	}

	@Test
	void multipleCommands() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			server.start();

			try (RemoteConsole rcon = RemoteConsole.builder("localhost:" + server.getPort(), "password")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()) {
				server.setCommandResponse("Response 1");
				RconResponse response1 = rcon.command("cmd1");
				assertThat(response1.body()).isEqualTo("Response 1");

				server.setCommandResponse("Response 2");
				RconResponse response2 = rcon.command("cmd2");
				assertThat(response2.body()).isEqualTo("Response 2");
			}
		}
	}

	@Test
	void largeResponse() throws Exception {
		try (MockRconServer server = new MockRconServer()) {
			String largeResponse = "x".repeat(4000);
			server.setCommandResponse(largeResponse);
			server.start();

			try (RemoteConsole rcon = RemoteConsole.builder("localhost:" + server.getPort(), "password")
				.connectTimeout(Duration.ofSeconds(5))
				.connect()) {
				RconResponse response = rcon.command("large");
				assertThat(response.body()).isEqualTo(largeResponse);
			}
		}
	}

	@Test
	void invalidHostFormat() {
		assertThatThrownBy(() -> RemoteConsole.connect("localhost", "password"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid host format");
	}

	@Test
	void invalidPortNumber() {
		assertThatThrownBy(() -> RemoteConsole.connect("localhost:abc", "password"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid port number");
	}

	private static class MockRconServer implements AutoCloseable {

		private final ServerSocket serverSocket;

		private final ExecutorService executor;

		private final AtomicReference<String> commandResponse;

		private final CountDownLatch startLatch;

		private volatile boolean authShouldFail = false;

		MockRconServer() throws IOException {
			this.serverSocket = new ServerSocket(0);
			this.executor = Executors.newCachedThreadPool();
			this.commandResponse = new AtomicReference<>("OK");
			this.startLatch = new CountDownLatch(1);
		}

		int getPort() {
			return this.serverSocket.getLocalPort();
		}

		void setCommandResponse(String response) {
			this.commandResponse.set(response);
		}

		void setAuthShouldFail(boolean shouldFail) {
			this.authShouldFail = shouldFail;
		}

		void start() {
			this.executor.submit(() -> {
				this.startLatch.countDown();
				try {
					while (!this.serverSocket.isClosed()) {
						Socket client = this.serverSocket.accept();
						this.executor.submit(() -> handleClient(client));
					}
				}
				catch (IOException ex) {
					// Server closed
				}
			});
			try {
				this.startLatch.await(5, TimeUnit.SECONDS);
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		private void handleClient(Socket client) {
			try (client) {
				InputStream in = client.getInputStream();
				OutputStream out = client.getOutputStream();

				Packet authPacket = readPacket(in);
				if (this.authShouldFail) {
					// Send auth response with wrong request ID to indicate failure
					sendPacket(out, -1, RESP_AUTH_RESPONSE, "");
					return;
				}
				sendPacket(out, authPacket.requestId, RESP_AUTH_RESPONSE, authPacket.body);

				while (!client.isClosed()) {
					Packet cmdPacket = readPacket(in);
					if (cmdPacket == null) {
						break;
					}
					sendPacket(out, cmdPacket.requestId, RESP_RESPONSE, this.commandResponse.get());
				}
			}
			catch (IOException ex) {
				// Client disconnected
			}
		}

		private Packet readPacket(InputStream in) throws IOException {
			byte[] sizeBytes = new byte[4];
			int read = in.read(sizeBytes);
			if (read < 4) {
				return null;
			}

			ByteBuffer sizeBuffer = ByteBuffer.wrap(sizeBytes);
			sizeBuffer.order(ByteOrder.LITTLE_ENDIAN);
			int size = sizeBuffer.getInt();

			byte[] data = new byte[size];
			int totalRead = 0;
			while (totalRead < size) {
				int r = in.read(data, totalRead, size - totalRead);
				if (r < 0) {
					return null;
				}
				totalRead += r;
			}

			ByteBuffer buffer = ByteBuffer.wrap(data);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			int requestId = buffer.getInt();
			int cmdType = buffer.getInt();

			byte[] bodyBytes = new byte[size - 10];
			buffer.get(bodyBytes);
			String body = new String(bodyBytes, 0, bodyBytes.length - 1, StandardCharsets.UTF_8);

			return new Packet(requestId, cmdType, body);
		}

		private void sendPacket(OutputStream out, int requestId, int responseType, String body) throws IOException {
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			int size = 10 + bodyBytes.length;

			ByteBuffer buffer = ByteBuffer.allocate(4 + size);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			buffer.putInt(size);
			buffer.putInt(requestId);
			buffer.putInt(responseType);
			buffer.put(bodyBytes);
			buffer.put((byte) 0);
			buffer.put((byte) 0);

			out.write(buffer.array());
			out.flush();
		}

		@Override
		public void close() throws IOException {
			this.executor.shutdownNow();
			this.serverSocket.close();
		}

		private record Packet(int requestId, int cmdType, String body) {
		}

	}

}
