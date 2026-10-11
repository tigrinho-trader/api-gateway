package com.tigrinhotrader.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do provedor OpenID Connect (Keycloak local por padrao; Auth0 ou outro so por configuracao) e da
 * checagem de conta. Os padroes e as variaveis de ambiente (AUTH_ISSUER, AUTH_JWKS_URI, AUTH_AUDIENCE,
 * AUTH_CLAIMS_NAMESPACE, USER_URL, AUTH_STATUS_CACHE, AUTH_STATUS_TIMEOUT) ficam no application.yml.
 *
 * @param emissor         valor esperado na claim iss (ex.: http://localhost:8180/realms/tigrinho)
 * @param jwksUri         onde buscar as chaves publicas (buscado so na primeira validacao)
 * @param audiencia       valor que precisa estar na claim aud (ex.: tigrinho-api)
 * @param namespaceClaims prefixo das claims customizadas (email, name, picture) usadas pelo Auth0
 * @param usuariosUrl     endereco do user-service
 * @param cacheStatus     por quanto tempo guardar o status da conta de cada usuario
 * @param timeoutStatus   tempo maximo da consulta de status no user-service
 */
@ConfigurationProperties("tigrinho.auth")
public record AuthPropriedades(
        String emissor,
        String jwksUri,
        String audiencia,
        String namespaceClaims,
        String usuariosUrl,
        Duration cacheStatus,
        Duration timeoutStatus) {
}
