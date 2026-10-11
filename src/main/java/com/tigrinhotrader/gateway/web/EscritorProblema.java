package com.tigrinhotrader.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Escreve um ProblemDetail (application/problem+json) direto na resposta, fora dos controllers. */
@Component
public class EscritorProblema {

    private final ObjectMapper mapper;

    public EscritorProblema(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Mono<Void> escrever(ServerWebExchange exchange, HttpStatus status, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        ServerHttpResponse resposta = exchange.getResponse();
        resposta.setStatusCode(status);
        resposta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return Mono.fromCallable(() -> mapper.writeValueAsBytes(problema))
                .flatMap(corpo -> resposta.writeWith(Mono.just(resposta.bufferFactory().wrap(corpo))));
    }
}
