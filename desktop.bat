@echo off
rem Настольное приложение: запуск/остановка бота и записи (сначала: mvn package)
chcp 65001 > nul
cd /d "%~dp0"
set JAR=target\salon-bot.jar
if not exist "%JAR%" (
  echo Не найден %JAR%. Соберите проект: mvn package
  pause
  exit /b 1
)
start "" javaw -Dloader.main=com.salon.desktop.DesktopApp -cp "%JAR%" org.springframework.boot.loader.launch.PropertiesLauncher %*
