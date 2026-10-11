package com.tigrinhotrader.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do Auth0 e da checagem de conta. Os padroes e as variaveis de ambiente
 * (AUTH0_DOMAIN, AUTH0_AUDIENCE, AUTH0_ISSUER, AUTH0_JWKS_URI, AUTH0_CLAIMS_NAMESPACE, USER_URL,
 * AUTH_STATUS_CACHE, AUTH_STATUS_TIMEOUT) ficam no application.yml.
 *
 * @param dominio         dominio do tenant no Auth0
 * @param audiencia       valor esperado na claim aud (identificador da API no Auth0)
 * @param emissor         valor esperado na claim iss
 * @param jwksUri         onde buscar as chaves publicas (buscado so na primeira validacao)
 * @param namespaceClaims prefixo das claims customizadas (email, name, picture)
 * @param usuariosUrl     endereco do user-service
 * @param cacheStatus     por quanto tempo guardar o status da conta de cada usuario
 * @param timeoutStatus   tempo maximo da consulta de status no user-service
 */
@ConfigurationProperties("tigrinho.auth")
public record AuthPropriedades(
        String dominio,
        String audiencia,
        String emissor,
        String jwksUri,
        String namespaceClaims,
        String usuariosUrl,
        Duration cacheStatus,
        Duration timeoutStatus) {
}
