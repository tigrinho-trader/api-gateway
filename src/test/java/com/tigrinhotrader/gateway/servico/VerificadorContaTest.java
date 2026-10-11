package com.tigrinhotrader.gateway.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.gateway.cliente.ClienteUsuarios;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class VerificadorContaTest {

    private final ClienteUsuarios cliente = mock(ClienteUsuarios.class);
    private final RelogioFalso relogio = new RelogioFalso();
    private VerificadorConta verificador;

    @BeforeEach
    void preparar() {
        verificador = new VerificadorConta(cliente, relogio, Duration.ofSeconds(30), 2);
        when(cliente.status("a")).thenReturn(Mono.just("ATIVO"));
        when(cliente.status("b")).thenReturn(Mono.just("BLOQUEADO"));
        when(cliente.status("c")).thenReturn(Mono.just(ClienteUsuarios.NAO_PROVISIONADO));
    }

    @Test
    void dentroDoTtlUsaOCache() {
        StepVerifier.create(verificador.status("b")).expectNext("BLOQUEADO").verifyComplete();
        relogio.avancar(Duration.ofSeconds(29));
        StepVerifier.create(verificador.status("b")).expectNext("BLOQUEADO").verifyComplete();
        verify(cliente, times(1)).status("b");
    }

    @Test
    void depoisDoTtlConsultaDeNovo() {
        StepVerifier.create(verificador.status("a")).expectNext("ATIVO").verifyComplete();
        relogio.avancar(Duration.ofSeconds(30));
        StepVerifier.create(verificador.status("a")).expectNext("ATIVO").verifyComplete();
        verify(cliente, times(2)).status("a");
    }

    @Test
    void erroNaoEntraNoCache() {
        when(cliente.status("x")).thenReturn(Mono.error(new IllegalStateException("fora do ar")));
        StepVerifier.create(verificador.status("x")).verifyError(IllegalStateException.class);
        StepVerifier.create(verificador.status("x")).verifyError(IllegalStateException.class);
        verify(cliente, times(2)).status("x");
        assertThat(verificador.tamanhoCache()).isZero();
    }

    @Test
    void cacheCheioVarreAsEntradasVencidas() {
        verificador.status("a").block();
        verificador.status("b").block();
        assertThat(verificador.tamanhoCache()).isEqualTo(2);

        relogio.avancar(Duration.ofMinutes(1));
        verificador.status("c").block();
        assertThat(verificador.tamanhoCache()).isEqualTo(1);
    }

    @Test
    void cacheCheioSemVencidasContinuaGuardando() {
        verificador.status("a").block();
        verificador.status("b").block();
        verificador.status("c").block();
        assertThat(verificador.tamanhoCache()).isEqualTo(3);
    }

    /** Relogio que so anda quando o teste manda. */
    static class RelogioFalso extends Clock {
        private Instant agora = Instant.parse("2026-01-01T00:00:00Z");

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
