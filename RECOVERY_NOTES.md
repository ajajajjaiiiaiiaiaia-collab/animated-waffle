# Recovery notes

- Input: `StarkModeration_(1)_(1)_(2)_(1)_1792081032972.jar`
- Decompiler: Vineflower 1.11.1
- Recovered source: 12 Java source files under `com.starkmc.moderation`
- The JAR contained compiled class files for the plugin, `plugin.yml`, `config.yml`, and the embedded Maven POM. It did not contain `.java` source files.
- Inner/nested classes are represented in their enclosing Java source files where appropriate.
- The Maven POM in the JAR identifies Java 21 and the Paper/LuckPerms APIs; the plugin descriptor reports version 1.2.0 while the embedded Maven POM reports artifact version 1.0.0. Both original metadata files are preserved unchanged.
- Decompilation can change formatting and some syntactic details; inspect behavior carefully before deploying or editing production configuration.
