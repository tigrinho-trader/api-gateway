-- Um banco por servico (database-per-service), no mesmo Postgres pra simplificar o ambiente local.
CREATE DATABASE trading;
CREATE DATABASE wallet;
CREATE DATABASE notification;
CREATE DATABASE usuarios;
-- Keycloak em producao (docker-compose.easypanel.yml); localmente ele usa o H2 do modo dev.
CREATE DATABASE keycloak;
