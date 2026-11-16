package com.tigrinhotrader.gateway.painel;

import java.nio.charset.StandardCharsets;

/** Senha e hash do painel usados nos testes (poucas iteracoes: so para o teste ser rapido). */
public final class SenhaPainelTestes {

    public static final String SENHA = "senha-de-teste-do-painel";
    public static final String HASH = SenhaPainel.gerar(SENHA, "sal-de-teste-16b".getBytes(StandardCharsets.UTF_8), 1000);

    private SenhaPainelTestes() {
    }
}
