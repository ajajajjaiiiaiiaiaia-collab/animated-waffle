# StarkModeration (recovered source)

This project was reconstructed from the uploaded `StarkModeration` plugin JAR. The Java files were decompiled from compiled `.class` files; they are not the original source files. Original comments, formatting, and any source-only material that was not packaged in the JAR cannot be recovered.

## Contents

- `src/main/java/` — recovered Java plugin source
- `src/main/resources/plugin.yml` and `config.yml` — copied from the JAR
- `pom.xml` — Maven build configuration copied from the JAR
- `RECOVERY_NOTES.md` — recovery details and caveats

## Build

Requires JDK 21 and Maven. From this directory run:

```sh
mvn clean package
```

The build produces `target/StarkModeration.jar`.
