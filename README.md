# rcon4j

A Java library for the RCON (Remote Console) protocol.

RCON is a TCP/IP-based protocol that allows remote administration of game servers, commonly used by Minecraft, Valve Source Engine games, and others.

## Requirements

- Java 17+

## Installation

### Maven

```xml
<dependency>
    <groupId>am.ik.rcon</groupId>
    <artifactId>rcon4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'am.ik.rcon:rcon4j:0.1.0'
```

## Usage

### Basic usage

```java
import am.ik.rcon.RemoteConsole;

public class Main {
    public static void main(String[] args) {
        try (var rcon = RemoteConsole.connect("localhost:25575", "password")) {
            System.out.println(rcon.command("list").body());
        }
    }
}
```

### With custom timeout

```java
import am.ik.rcon.RemoteConsole;
import java.time.Duration;

try (var rcon = RemoteConsole.builder("localhost:25575", "password")
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(60))
        .connect()) {
    var response = rcon.command("list");
    System.out.println(response.body());
}
```

### Separate write and read

When sending multiple commands in quick succession, you can use `write()` and `read()` separately to batch commands and match responses by request ID.

```java
import am.ik.rcon.RemoteConsole;
import java.util.HashMap;

try (var rcon = RemoteConsole.connect("localhost:25575", "password")) {
    // Send multiple commands
    var commands = new HashMap<Integer, String>();
    commands.put(rcon.write("list"), "list");
    commands.put(rcon.write("seed"), "seed");

    // Read responses and match by request ID
    for (int i = 0; i < commands.size(); i++) {
        var response = rcon.read();
        String command = commands.get(response.requestId());
        System.out.println(command + ": " + response.body());
    }
}
```

## License

Apache License 2.0
