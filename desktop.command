#!/usr/bin/env bash
# macOS: запуск двойным щелчком в Finder. Окно Терминала можно закрыть — приложение продолжит работу.
cd "$(dirname "$0")" || exit 1
nohup ./desktop.sh > /dev/null 2>&1 &
