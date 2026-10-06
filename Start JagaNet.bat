@echo off
rem Double-click this file to set up and try JagaNet. See HOW-TO-TRY.md.
title JagaNet
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\JagaNet.ps1"
if errorlevel 1 pause
