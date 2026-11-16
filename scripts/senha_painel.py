#!/usr/bin/env python3
"""Gera a senha do painel do super-admin (/painel) para o ambiente do api-gateway.

    python scripts/senha_painel.py                 # pede a senha e imprime as linhas para o .env / EasyPanel
    python scripts/senha_painel.py --env ../.env   # idem, e grava (ou troca) as linhas nesse arquivo

A senha e digitada sem aparecer na tela e NUNCA e gravada: so o hash dela (PBKDF2-SHA256, 600 mil iteracoes,
sal aleatorio), no formato que o gateway confere: pbkdf2_sha256:<iteracoes>:<sal>:<hash> (base64url).
Tambem gera o PAINEL_SEGREDO, a chave que assina os logins do painel (trocar o segredo derruba quem estiver logado).
"""

import argparse
import base64
import getpass
import hashlib
import secrets
import sys
from pathlib import Path

ITERACOES = 600_000
TAMANHO_MINIMO = 10


def b64(dados: bytes) -> str:
    return base64.urlsafe_b64encode(dados).decode().rstrip("=")


def gerar_hash(senha: str) -> str:
    sal = secrets.token_bytes(16)
    derivado = hashlib.pbkdf2_hmac("sha256", senha.encode("utf-8"), sal, ITERACOES)
    return f"pbkdf2_sha256:{ITERACOES}:{b64(sal)}:{b64(derivado)}"


def pedir_senha() -> str:
    while True:
        senha = getpass.getpass(f"Senha do painel (pelo menos {TAMANHO_MINIMO} caracteres): ")
        if len(senha) < TAMANHO_MINIMO:
            print(f"Muito curta: use pelo menos {TAMANHO_MINIMO} caracteres.")
            continue
        if getpass.getpass("Repita a senha: ") != senha:
            print("As senhas nao conferem; tente de novo.")
            continue
        return senha


def gravar(caminho: Path, valores: dict) -> None:
    linhas = caminho.read_text(encoding="utf-8").splitlines() if caminho.exists() else []
    faltando = dict(valores)
    for i, linha in enumerate(linhas):
        nome = linha.split("=", 1)[0].strip()
        if nome in faltando:
            linhas[i] = f"{nome}={faltando.pop(nome)}"
    if faltando:
        if linhas and linhas[-1].strip():
            linhas.append("")
        linhas.append("# Painel do super-admin (gerado por scripts/senha_painel.py)")
        linhas.extend(f"{nome}={valor}" for nome, valor in faltando.items())
    caminho.write_text("\n".join(linhas) + "\n", encoding="utf-8")


def main() -> int:
    argumentos = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    argumentos.add_argument("--env", type=Path, help="arquivo .env onde gravar as linhas (troca as que ja existirem)")
    argumentos.add_argument("--usuario", default="super-admin-oscar", help="login do painel (padrao super-admin-oscar)")
    opcoes = argumentos.parse_args()

    senha = pedir_senha()
    print("Gerando o hash (leva alguns segundos)...")
    valores = {
        "PAINEL_USUARIO": opcoes.usuario,
        "PAINEL_SENHA_HASH": gerar_hash(senha),
        "PAINEL_SEGREDO": secrets.token_urlsafe(48),
    }
    del senha

    if opcoes.env:
        gravar(opcoes.env, valores)
        print(f"Gravado em {opcoes.env}. Reinicie o api-gateway para valer.")
    print("\nNo EasyPanel (servico api-gateway > Environment), defina:\n")
    for nome, valor in valores.items():
        print(f"{nome}={valor}")
    print(f"\nDepois entre em /painel com o usuario {opcoes.usuario} e a senha que voce digitou.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyboardInterrupt, EOFError):
        print("\nCancelado.", file=sys.stderr)
        sys.exit(1)
