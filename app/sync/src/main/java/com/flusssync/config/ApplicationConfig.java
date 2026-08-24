package com.flusssync.config;

/** Parsed form of {@code config/apps/sync/application.yaml}. */
public class ApplicationConfig {

    public String apiVersion;
    public String kind;
    public Spec spec = new Spec();

    public static class Spec {
        public Parsing parsing = new Parsing();
        public Retention retention = new Retention();
        public Health health = new Health();
    }

    public static class Parsing {
        public String nullLiteral = "";
        public String timestampFormat = "yyyy-MM-dd'T'HH:mm:ss";
        public String dateFormat = "yyyy-MM-dd";
    }

    public static class Retention {
        public boolean enabled = true;
        public int days = 15;
    }

    public static class Health {
        public boolean enabled = true;
        public int port = 8080;
        public String path = "/healthz";
    }

    /** The default config used when no application.yaml is present. */
    public static ApplicationConfig defaults() {
        return new ApplicationConfig();
    }
}
