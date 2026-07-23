package tim4it.login.eko;

import tim4it.login.eko.config.Config;

public class Main {

    static void main(String[] ignore) {
        new Startup(Config.load()).run();
    }
}
