package com.tigrinhotrader.gateway.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Respostas de erro da cadeia de seguranca.
 * - Em /api/**: sem token ou token invalido -> 401 problem+json com WWW-Authenticate: Bearer.
 * - Fora de /api/** (e fora do actuator publico): o caminho "nao existe" para quem esta de fora -> 404,
 *   com ou sem token, para nao revelar o que o gateway tem por dentro.
 */
@Component
public class RespostasSeguranca implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private static final PathPattern API = PathPatternParser.defaultInstance.parse("/api/**");

    private final EscritorProblema escritor;

    public RespostasSeguranca(EscritorProblema escritor) {
        this.escritor = escritor;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException erro) {
        if (!API.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            return naoEncontrado(exchange);
        }
        boolean tokenInvalido = erro instanceof OAuth2AuthenticationException;
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                tokenInvalido ? "Bearer error=\"invalid_token\"" : "Bearer");
        return escritor.escrever(exchange, HttpStatus.UNAUTHORIZED, "Não autenticado",
                tokenInvalido ? "Token de acesso inválido ou expirado" : "Token de acesso ausente");
    }

    /** So acontece fora de /api/** (dentro, tudo e publico ou so exige estar autenticado). */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException erro) {
        return naoEncontrado(exchange);
    }

    private Mono<Void> naoEncontrado(ServerWebExchange exchange) {
        return escritor.escrever(exchange, HttpStatus.NOT_FOUND, "Não encontrado", "Recurso não encontrado");
    }
}
