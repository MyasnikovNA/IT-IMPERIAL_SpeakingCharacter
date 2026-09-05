#!/bin/sh
# Запускает закреплённый Gradle Wrapper без необходимости устанавливать Gradle в систему.

# На macOS Homebrew не всегда добавляет установленную JDK в системный PATH.
# Явно заданный JAVA_HOME имеет приоритет над локальным значением по умолчанию.
if [ -z "${JAVA_HOME:-}" ] && [ -x "/opt/homebrew/opt/openjdk@17/bin/java" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
