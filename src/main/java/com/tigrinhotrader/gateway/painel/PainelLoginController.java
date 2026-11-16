package com.tigrinhotrader.gateway.painel;

import com.tigrinhotrader.gateway.config.PainelPropriedades;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Login do painel do super-admin: {@code POST /api/painel/login {"usuario","senha"}} devolve um token que vale
 * {@code tigrinho.painel.validade}. Depois de 5 erros em 15 minutos o login fica travado por 15 minutos; cada erro
 * ainda espera 1 s antes de responder.
 */
@RestController
public class PainelLoginController {

    private static final Logger log = LoggerFactory.getLogger(PainelLoginController.class);
    static final int MAX_ERROS = 5;
    static final Duration JANELA = Duration.ofMinutes(15);
    static final Duration ESPERA_ERRO = Duration.ofSeconds(1);

    public record Login(String usuario, String senha) {
    }

    public record SessaoPainel(String token, Instant expiraEm, String usuario) {
    }

    private final PainelPropriedades propriedades;
    private final TokenPainel tokens;
    private final Clock relogio;
    private final Deque<Instant> erros = new ArrayDeque<>();

    public PainelLoginController(PainelPropriedades propriedades, TokenPainel tokens, Clock relogio) {
        this.propriedades = propriedades;
        this.tokens = tokens;
        this.relogio = relogio;
    }

    @PostMapping("/api/painel/login")
    public Mono<ResponseEntity<Object>> login(@RequestBody(required = false) Login login) {
        if (!propriedades.habilitado()) {
            return Mono.just(problema(HttpStatus.SERVICE_UNAVAILABLE,
                    "Painel desligado: defina PAINEL_SENHA_HASH e PAINEL_SEGREDO no ambiente do gateway"));
        }
        Instant agora = relogio.instant();
        Instant travadoAte = travadoAte(agora);
        if (travadoAte != null) {
            long minutos = Math.max(1, Duration.between(agora, travadoAte).toMinutes() + 1);
            return Mono.just(problema(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas erradas: espere " + minutos + " min para tentar de novo"));
        }
        String usuario = login == null ? null : login.usuario();
        String senha = login == null ? null : login.senha();
        // PBKDF2 e lento de proposito: fora da thread do servidor
        return Mono.fromCallable(() -> propriedades.usuario().equals(usuario)
                        & SenhaPainel.confere(senha, propriedades.senhaHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(certo -> {
                    if (certo) {
                        limparErros();
                        Instant expira = relogio.instant().plus(propriedades.validade());
                        log.info("Painel: login de {}", propriedades.usuario());
                        return Mono.just(ResponseEntity.ok((Object) new SessaoPainel(
                                tokens.emitir(propriedades.usuario(), expira), expira, propriedades.usuario())));
                    }
                    registrarErro(relogio.instant());
                    log.warn("Painel: login recusado (usuario ou senha errados)");
                    return Mono.delay(ESPERA_ERRO).thenReturn(problema(HttpStatus.UNAUTHORIZED,
                            "Usuário ou senha incorretos"));
                });
    }

    private synchronized Instant travadoAte(Instant agora) {
        while (!erros.isEmpty() && !erros.peekFirst().isAfter(agora.minus(JANELA))) {
            erros.pollFirst();
        }
        return erros.size() >= MAX_ERROS ? erros.peekLast().plus(JANELA) : null;
    }

    private synchronized void registrarErro(Instant agora) {
        erros.addLast(agora);
    }

    private synchronized void limparErros() {
        erros.clear();
    }

    private static ResponseEntity<Object> problema(HttpStatus status, String detalhe) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detalhe));
    }
}
