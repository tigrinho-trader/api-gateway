package com.tigrinhotrader.gateway.web;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Recusa (400) caminhos que o servico de destino poderia normalizar para outro lugar, como
 * /api/usuarios/../interno/... ou /api/usuarios/..;/interno/... (o Tomcat resolve ".." e ignora ";...").
 * Garante que nenhuma rota externa chegue em /interno/** dos servicos.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCaminhoSeguro implements WebFilter {

    // segmento "." ou "..", ';' (parametros de caminho), barra invertida e as versoes codificadas de . / \
    private static final Pattern SUSPEITO = Pattern.compile("(^|/)\\.{1,2}(/|$)|;|\\\\|%2e|%2f|%5c");

    private final EscritorProblema escritor;

    public FiltroCaminhoSeguro(EscritorProblema escritor) {
        this.escritor = escritor;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String caminho = exchange.getRequest().getURI().getRawPath().toLowerCase(Locale.ROOT);
        if (SUSPEITO.matcher(caminho).find()) {
            return escritor.escrever(exchange, HttpStatus.BAD_REQUEST, "Caminho inválido",
                    "O caminho da requisição não é aceito pelo gateway");
        }
        return chain.filter(exchange);
    }
}
