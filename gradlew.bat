@rem gradlew.bat —— Gradle Wrapper 启动脚本（Windows）
@rem 用户本地若装有 JDK17，双击或在 cmd 里运行 gradlew.bat 即可编译。
@echo off
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if "%JAVA_HOME%"=="" (
  set JAVACMD=java
) else (
  set JAVACMD=%JAVA_HOME%\bin\java
)

"%JAVACMD%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
