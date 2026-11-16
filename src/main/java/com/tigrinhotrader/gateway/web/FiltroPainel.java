package com.tigrinhotrader.gateway.web;

import com.tigrinhotrader.gateway.painel.TokenPainel;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Painel do super-admin nas rotas {@code /api/painel/**}: exige o token do login do painel
 * ({@code Authorization: Bearer ...}) e, valido, poe {@value #ADMIN} com o usuario do painel. Em TODA rota apaga os
 * cabecalhos X-Painel-* que vieram do cliente, entao so o gateway marca um pedido como do painel.
 */
@Component
public class FiltroPainel implements GlobalFilter, Ordered {

    public static final int ORDEM = FiltroIdentidade.ORDEM - 10;
    static final String PREFIXO = "x-painel-";
    static final String ADMIN = "X-Painel-Admin";
    static final String ROTAS = "/api/painel/";

    private final TokenPainel tokens;
    private final EscritorProblema escritor;
    private final Clock relogio;

    public FiltroPainel(TokenPainel tokens, EscritorProblema escritor, Clock relogio) {
        this.tokens = tokens;
        this.escritor = escritor;
        this.relogio = relogio;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean doPainel = exchange.getRequest().getURI().getRawPath().startsWith(ROTAS);
        Optional<String> admin = doPainel ? tokens.validar(bearer(exchange), relogio.instant()) : Optional.empty();
        if (doPainel && admin.isEmpty()) {
            return escritor.escrever(exchange, HttpStatus.UNAUTHORIZED, "Login do painel",
                    "Entre no painel do super-admin de novo");
        }
        return chain.filter(exchange.mutate().request(r -> r.headers(h -> {
            limpar(h);
            admin.ifPresent(usuario -> h.set(ADMIN, usuario));
        })).build());
    }

    @Override
    public int getOrder() {
        return ORDEM;
    }

    private static String bearer(ServerWebExchange exchange) {
        String valor = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return valor != null && valor.regionMatches(true, 0, "Bearer ", 0, 7) ? valor.substring(7).trim() : null;
    }

    private static void limpar(HttpHeaders cabecalhos) {
        List<String> nomes = cabecalhos.keySet().stream()
                .filter(nome -> nome.toLowerCase(Locale.ROOT).startsWith(PREFIXO))
                .toList();
        nomes.forEach(cabecalhos::remove);
    }
}
