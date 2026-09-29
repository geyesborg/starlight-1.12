@echo off
rem Wrapper: runs Gradle with the requested Adoptium JDK 25.
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.7-hotspot"
rem Gradle's own native-platform library calls System::load, a restricted method on JDK 25.
rem The launcher JVM does not read org.gradle.jvmargs, so grant native access here.
set "GRADLE_OPTS=%GRADLE_OPTS% --enable-native-access=ALL-UNNAMED"
cd /d "%~dp0"
call gradlew.bat %*
