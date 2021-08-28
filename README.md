# MudEngine

A Dikumud-inspired MUD engine, written in Kotlin.

## Description

"A Multi-User Dungeon engine, for a more civilized age."
 
MudEngine is a framework that forms the foundation for MUDs that want a blend of the oldschool Dikumud game loop, combined with a more modern programming experience.

## Getting Started

### Dependencies

Written in Kotlin for Java SE 16 and tested/compiled on 64-bit Windows and Linux.

Uses the following Maven packages (via Gradle):
#### Logging
* [org.apache.logging.log4j:log4j-api](https://logging.apache.org/log4j/2.x/)
* [org.apache.logging.log4j:log4j-core](https://logging.apache.org/log4j/2.x/)
* [org.apache.logging.log4j:log4j-slf4j-impl](https://logging.apache.org/log4j/2.x/log4j-slf4j-impl/)
* [org.apache.logging.log4j:log4j-api-kotlin](https://logging.apache.org/log4j/kotlin/)
* [io.github.microutils:kotlin-logging-jvm](https://github.com/MicroUtils/kotlin-logging)
#### Command-line Parsing
* [com.github.ajalt.clikt:clikt](https://github.com/ajalt/clikt/)

### Installing / Building

1. Clone this GitHub repository
2. Build with Gradle: ```gradlew build```

### Executing program

* Windows: ```bin/MudEngine.bat```
* Linux: ```sh bin/MudEngine```

## Help

If the application fails to run, check the stack trace (if any), and/or the default log file: syslog.log.

## Authors

* [Alan K. Miles](https://github.com/Silvertower/)

## Version History

* No official releases have been made.

## License

This project is licensed under the LGPL 3.0 License - see the LICENSE.md file for details.

## Acknowledgments

* [Dikumud](https://dikumud.com/)
* [Roninmud](http://roninmud.org/)
