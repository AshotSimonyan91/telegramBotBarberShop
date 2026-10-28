#!/usr/bin/env bash
# Запуск бота из собранного jar (сначала: mvn package)
cd "$(dirname "$0")" || exit 1
JAR="target/salon-bot.jar"
if [ ! -f "$JAR" ]; then
  echo "Не найден $JAR. Соберите проект: mvn package"
  exit 1
fi
exec java -Djava.awt.headless=true -jar "$JAR" "$@"
