# COMP535 Project Starter Code

## Requirements

To compile and run this project, you need:

- **Java**: Version 8 or higher (JDK 8+)
- **Maven**: Version 3.0 or higher

### Checking Your Versions

You can check if you have the required versions installed:

```bash
java -version
mvn -version
```

If you don't have these installed, please install them before proceeding.

## Building the Project

To compile the project:

```bash
mvn clean compile
```

To compile and create a JAR file:

```bash
mvn clean package
```

The compiled classes will be in `target/classes/` directory.

## Running the Program

Create an executable JAR with dependencies and run it:

```bash
mvn clean package assembly:single
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf
```

## Troubleshooting

### "JAVA_HOME not set" error
- Make sure Java is installed and JAVA_HOME environment variable points to your Java installation
- On Linux/Mac: `export JAVA_HOME=/path/to/java`
- On Windows: Set JAVA_HOME in System Environment Variables

### "Maven not found" error
- Make sure Maven is installed and added to your PATH
- Verify with: `mvn -version`

### Compilation errors
- Ensure you have Java 8 or higher installed
- Ensure you have Maven 3.0 or higher installed
- Try: `mvn clean compile` to rebuild from scratch

### "Could not find or load main class" error
- Make sure you built the JAR file: `mvn clean package assembly:single`
- Verify the JAR exists: `ls target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar`
- Rebuild the project: `mvn clean package assembly:single`
- Use the correct command: `java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf`

### Maven settings.xml warning
- If you see a warning about "Unrecognised tag: 'blocked'" in Maven settings.xml, you can safely ignore it
- This is a system-level Maven configuration issue and does not affect the build
- The build will still succeed despite this warning

## Project Structure

```
src/
  main/
    java/          # Source code
  resources/       # Configuration files
conf/              # Router configuration files
target/            # Compiled classes (generated)
```
