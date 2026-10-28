@echo off
rem Запуск бота из собранного jar (сначала: mvn package)
chcp 65001 > nul
cd /d "%~dp0"
set JAR=target\salon-bot.jar
if not exist "%JAR%" (
  echo Не найден %JAR%. Соберите проект: mvn package
  pause
  exit /b 1
)
java -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -jar "%JAR%" %*
pause
