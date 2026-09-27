@echo off
cd /d D:\charan\OrderService
set JAVA_HOME=C:\Users\vidya\.jdks\corretto-23.0.2
set PATH=%JAVA_HOME%\bin;%PATH%
D:\charan\OrderService\mvnw.cmd spring-boot:run
