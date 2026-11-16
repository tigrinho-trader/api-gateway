package com.tigrinhotrader.gateway.painel;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigrinhotrader.gateway.config.PainelPropriedades;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Senha do painel (PBKDF2 no formato do scripts/senha_painel.py) e o token assinado do login. */
class SenhaETokenPainelTest {

    private static final String SEGREDO = "segredo-de-teste-do-painel-com-mais-de-32-caracteres";
    private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");

    @Test
    void senhaCertaConfereEErradaNao() {
        assertThat(SenhaPainel.confere(SenhaPainelTestes.SENHA, SenhaPainelTestes.HASH)).isTrue();
        assertThat(SenhaPainel.confere("outra", SenhaPainelTestes.HASH)).isFalse();
        assertThat(SenhaPainel.confere(null, SenhaPainelTestes.HASH)).isFalse();
        assertThat(SenhaPainel.confere(SenhaPainelTestes.SENHA, "")).isFalse();
        assertThat(SenhaPainel.confere(SenhaPainelTestes.SENHA, "md5:1:abc:def")).isFalse();
        assertThat(SenhaPainel.confere(SenhaPainelTestes.SENHA, "pbkdf2_sha256:x:abc:def")).isFalse();
    }

    @Test
    void confereOHashGeradoPeloScriptEmPython() {
        // gerado em Python: hashlib.pbkdf2_hmac("sha256", b"minha senha", b"0123456789abcdef", 1000), base64url
        // sem padding (o mesmo que o scripts/senha_painel.py faz, com mais iteracoes)
        String doScript = "pbkdf2_sha256:1000:MDEyMzQ1Njc4OWFiY2RlZg:PGpqYnb8lZfqQ7nWnUqNYwzXueaDl4YVjOB-GFMEnTQ";
        assertThat(SenhaPainel.confere("minha senha", doScript)).isTrue();
        assertThat(SenhaPainel.confere("minha senhA", doScript)).isFalse();
    }

    @Test
    void tokenValeAteExpirarESoComOSegredoCerto() {
        TokenPainel tokens = new TokenPainel(new PainelPropriedades("super-admin-oscar", "x", SEGREDO, null));
        String token = tokens.emitir("super-admin-oscar", AGORA.plus(Duration.ofHours(8)));

        assertThat(tokens.validar(token, AGORA)).contains("super-admin-oscar");
        assertThat(tokens.validar(token, AGORA.plus(Duration.ofHours(8)))).isEmpty();          // expirou
        TokenPainel outroSegredo = new TokenPainel(new PainelPropriedades(null, "x", SEGREDO + "-outro", null));
        assertThat(outroSegredo.validar(token, AGORA)).isEmpty();
        assertThat(tokens.validar(token + "x", AGORA)).isEmpty();
        assertThat(tokens.validar("abc", AGORA)).isEmpty();
        assertThat(tokens.validar(null, AGORA)).isEmpty();
    }

    @Test
    void semSegredoOuSemHashOPainelFicaDesligado() {
        assertThat(new PainelPropriedades(null, null, null, null).habilitado()).isFalse();
        assertThat(new PainelPropriedades(null, "hash", "curto", null).habilitado()).isFalse();
        assertThat(new PainelPropriedades(null, "hash", SEGREDO, null).habilitado()).isTrue();
        assertThat(new PainelPropriedades(null, null, null, null).usuario()).isEqualTo("super-admin-oscar");
        assertThat(new PainelPropriedades(null, "h", SEGREDO, null).toString()).doesNotContain(SEGREDO);
        // sem segredo, nenhum token vale
        TokenPainel semSegredo = new TokenPainel(new PainelPropriedades(null, null, null, null));
        assertThat(semSegredo.validar(semSegredo.emitir("super-admin-oscar", AGORA.plusSeconds(60)), AGORA)).isEmpty();
    }
}
