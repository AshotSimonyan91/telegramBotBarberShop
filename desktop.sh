#!/usr/bin/env bash
# Настольное приложение: запуск/остановка бота и записи (сначала: mvn package)
cd "$(dirname "$0")" || exit 1
JAR="target/salon-bot.jar"
if [ ! -f "$JAR" ]; then
  echo "Не найден $JAR. Соберите проект: mvn package"
  exit 1
fi
exec java -Xdock:name="Салон" -Dloader.main=com.salon.desktop.DesktopApp \
  -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
