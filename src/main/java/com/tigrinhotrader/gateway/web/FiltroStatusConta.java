package com.tigrinhotrader.gateway.web;

import com.tigrinhotrader.gateway.servico.VerificadorConta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Barra contas bloqueadas/excluidas antes de rotear (so requisicoes autenticadas).
 * BLOQUEADO / EXCLUIDO -> 403; 404 no user-service (primeiro login) -> segue;
 * erro ou timeout do user-service -> 503 (falha fechada).
 */
@Component
public class FiltroStatusConta implements GlobalFilter, Ordered {

    public static final int ORDEM = FiltroIdentidade.ORDEM - 50;

    private static final Logger log = LoggerFactory.getLogger(FiltroStatusConta.class);

    private final VerificadorConta verificador;
    private final EscritorProblema escritor;

    public FiltroStatusConta(VerificadorConta verificador, EscritorProblema escritor) {
        this.verificador = verificador;
        this.escritor = escritor;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .flatMap(autenticacao -> verificador.status(autenticacao.getName())
                        .map(Decisao::doStatus)
                        .onErrorResume(erro -> {
                            log.warn("Falha ao consultar status da conta {}: {}", autenticacao.getName(), erro.toString());
                            return Mono.just(Decisao.INDISPONIVEL);
                        }))
                .defaultIfEmpty(Decisao.LIBERADA)
                .flatMap(decisao -> decisao == Decisao.LIBERADA
                        ? chain.filter(exchange)
                        : escritor.escrever(exchange, decisao.status, decisao.titulo, decisao.detalhe));
    }

    @Override
    public int getOrder() {
        return ORDEM;
    }

    enum Decisao {
        LIBERADA(HttpStatus.OK, null, null),
        BLOQUEADA(HttpStatus.FORBIDDEN, "Conta bloqueada", "Conta bloqueada"),
        EXCLUIDA(HttpStatus.FORBIDDEN, "Conta excluída", "Conta excluída"),
        INDISPONIVEL(HttpStatus.SERVICE_UNAVAILABLE, "Serviço indisponível",
                "Não foi possível verificar a conta agora; tente de novo em instantes");

        final HttpStatus status;
        final String titulo;
        final String detalhe;

        Decisao(HttpStatus status, String titulo, String detalhe) {
            this.status = status;
            this.titulo = titulo;
            this.detalhe = detalhe;
        }

        /** Qualquer status diferente de BLOQUEADO/EXCLUIDO (ATIVO, nao provisionado...) libera. */
        static Decisao doStatus(String status) {
            return switch (status) {
                case "BLOQUEADO" -> BLOQUEADA;
                case "EXCLUIDO" -> EXCLUIDA;
                default -> LIBERADA;
            };
        }
    }
}
