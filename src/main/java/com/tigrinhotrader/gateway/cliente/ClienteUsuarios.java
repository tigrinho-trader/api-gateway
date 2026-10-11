package com.tigrinhotrader.gateway.cliente;

import com.tigrinhotrader.gateway.config.AuthPropriedades;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Consulta o status da conta no user-service (GET /interno/usuarios/{id}/status). */
@Component
public class ClienteUsuarios {

    /** Status devolvido quando o user-service responde 404 (usuario ainda nao provisionado). */
    public static final String NAO_PROVISIONADO = "NAO_PROVISIONADO";

    private final WebClient webClient;
    private final Duration timeout;

    public ClienteUsuarios(WebClient.Builder builder, AuthPropriedades propriedades) {
        this.webClient = builder.baseUrl(propriedades.usuariosUrl()).build();
        this.timeout = propriedades.timeoutStatus();
    }

    /**
     * Status da conta ("ATIVO", "BLOQUEADO", "EXCLUIDO"...) ou {@link #NAO_PROVISIONADO}.
     * Erro do user-service, resposta sem status ou timeout viram erro no Mono.
     */
    public Mono<String> status(String usuarioId) {
        // o template codifica o id (auth0|abc -> auth0%7Cabc)
        return webClient.get().uri("/interno/usuarios/{id}/status", usuarioId)
                .exchangeToMono(resposta -> {
                    if (resposta.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return resposta.releaseBody().thenReturn(NAO_PROVISIONADO);
                    }
                    if (resposta.statusCode().is2xxSuccessful()) {
                        return resposta.bodyToMono(RespostaStatus.class).mapNotNull(RespostaStatus::status);
                    }
                    return resposta.createError();
                })
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("user-service respondeu sem status")))
                .timeout(timeout);
    }

    public record RespostaStatus(String status) {
    }
}
