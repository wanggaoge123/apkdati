#!/bin/sh

#
# gradlew —— Gradle Wrapper 启动脚本（Unix/Linux/macOS）
# GitHub Actions (ubuntu) 用它执行 ./gradlew assembleRelease 自动编译 APK。
# 用户本地（Windows）一般用 gradlew.bat；两者配套。
#

# 尝试定位 Java（CI 已装 JDK17；本地若未装则提示）
APP_HOME=$( cd "$(dirname "$0")" && pwd )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# 用系统 java 运行 wrapper；若没有 java 给出友好提示
if [ -z "$JAVA_HOME" ] ; then
  JAVACMD=$(command -v java)
else
  JAVACMD=$JAVA_HOME/bin/java
fi
if [ ! -x "$JAVACMD" ] && [ -z "$JAVACMD" ] ; then
  echo "错误：未找到 Java 运行环境(JRE)。请先安装 JDK 17。" >&2
  exit 1
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
