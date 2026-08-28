-- Set SUSUMONITOR_LOCAL_DB_PASSWORD in the client environment before running,
-- or replace <LOCAL_DEV_PASSWORD> interactively. Never reuse it in production.

CREATE DATABASE IF NOT EXISTS `susumonitor`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'susumonitor'@'localhost'
    IDENTIFIED BY '<LOCAL_DEV_PASSWORD>';

CREATE USER IF NOT EXISTS 'susumonitor'@'127.0.0.1'
    IDENTIFIED BY '<LOCAL_DEV_PASSWORD>';

GRANT ALL PRIVILEGES ON `susumonitor`.* TO 'susumonitor'@'localhost';
GRANT ALL PRIVILEGES ON `susumonitor`.* TO 'susumonitor'@'127.0.0.1';

FLUSH PRIVILEGES;
