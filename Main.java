import am.ik.rcon.RemoteConsole;

void main() {
    try (var rcon = RemoteConsole.connect("192.168.11.150:35575", "yadon1234")) {
        System.out.println(rcon.command("whitelist list").body());
    }
}

