package com.tigrinhotrader.gateway.servico;

import com.tigrinhotrader.gateway.cliente.ClienteUsuarios;
import com.tigrinhotrader.gateway.config.AuthPropriedades;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Status da conta de cada usuario, com cache em memoria por sub (TTL = AUTH_STATUS_CACHE).
 * So guarda respostas validas (inclusive 404); erro do user-service nao entra no cache.
 */
@Service
public class VerificadorConta {

    /** Acima disso, as entradas vencidas sao varridas antes de guardar uma nova. */
    static final int LIMITE_ENTRADAS = 10_000;

    private final ClienteUsuarios cliente;
    private final Clock clock;
    private final Duration ttl;
    private final int limite;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    @Autowired
    public VerificadorConta(ClienteUsuarios cliente, Clock clock, AuthPropriedades propriedades) {
        this(cliente, clock, propriedades.cacheStatus(), LIMITE_ENTRADAS);
    }

    VerificadorConta(ClienteUsuarios cliente, Clock clock, Duration ttl, int limite) {
        this.cliente = cliente;
        this.clock = clock;
        this.ttl = ttl;
        this.limite = limite;
    }

    public Mono<String> status(String usuarioId) {
        Instant agora = clock.instant();
        Entrada guardada = cache.get(usuarioId);
        if (guardada != null && guardada.validaEm(agora)) {
            return Mono.just(guardada.status());
        }
        return cliente.status(usuarioId).doOnNext(status -> guardar(usuarioId, status));
    }

    private void guardar(String usuarioId, String status) {
        Instant agora = clock.instant();
        if (cache.size() >= limite) {
            cache.values().removeIf(entrada -> !entrada.validaEm(agora));
        }
        cache.put(usuarioId, new Entrada(status, agora.plus(ttl)));
    }

    int tamanhoCache() {
        return cache.size();
    }

    private record Entrada(String status, Instant expiraEm) {
        boolean validaEm(Instant instante) {
            return instante.isBefore(expiraEm);
        }
    }
}
