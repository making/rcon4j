# rcon4j

[![Maven Central](https://img.shields.io/maven-central/v/am.ik.rcon/rcon4j.svg)](https://search.maven.org/search?q=g:am.ik.rcon%20AND%20a:rcon4j)


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

### Using with JShell

You can quickly test rcon4j using JShell without setting up a project.

```bash
jshell --class-path rcon4j-0.1.0.jar
```

```java
jshell> import am.ik.rcon.*

jshell> var rcon = RemoteConsole.connect("localhost:25575", "password")
rcon ==> am.ik.rcon.RemoteConsole@6a6824be

jshell> rcon.command("list").body()
$3 ==> "There are 0 of a max of 20 players online:"

jshell> rcon.command("seed").body()
$4 ==> "Seed: [-1234567890]"

jshell> rcon.close()
```

## License

Apache License 2.0
