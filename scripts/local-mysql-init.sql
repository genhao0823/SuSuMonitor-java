-- SuSuMonitor local MySQL initialization script.
-- This script is only for local development. Do not use this password in production.

CREATE DATABASE IF NOT EXISTS `susumonitor`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'susumonitor'@'localhost'
    IDENTIFIED BY '732682';

CREATE USER IF NOT EXISTS 'susumonitor'@'127.0.0.1'
    IDENTIFIED BY '732682';

GRANT ALL PRIVILEGES ON `susumonitor`.* TO 'susumonitor'@'localhost';
GRANT ALL PRIVILEGES ON `susumonitor`.* TO 'susumonitor'@'127.0.0.1';

FLUSH PRIVILEGES;
