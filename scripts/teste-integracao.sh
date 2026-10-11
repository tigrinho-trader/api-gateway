#!/usr/bin/env bash
# Teste de integracao ponta a ponta do Tigrinho Trader.
# Precisa do ambiente no ar (docker compose up --build) e passa por todas as setas do diagrama:
#   gateway -> market-data / wallet / trading (REST)
#   trading -> wallet e market-data (REST)
#   market-data -> trading (preco.atualizado) e trading -> wallet (ordem.executada) via RabbitMQ
#
# Desde a Etapa 3 o gateway exige um access token OpenID Connect (o X-Usuario-Id vindo do cliente e
# descartado). Com o Keycloak local do compose: entre no jogo (http://localhost:3000) com admin / admin123,
# abra o DevTools > Network, clique numa chamada /api/... e copie o cabecalho Authorization (sem "Bearer ").
# O cliente flecha-certeira e publico (PKCE) e nao aceita grant_type=password, por isso o token vem do
# navegador. Ele vale 5 minutos: rode o script logo depois de copiar. Detalhes no README.
#
#   TOKEN=eyJ... ./scripts/teste-integracao.sh
#   TOKEN=eyJ... GATEWAY_URL=http://localhost:8090 ./scripts/teste-integracao.sh
#
# Sai com codigo 1 se alguma verificacao falhar.

GW=${GATEWAY_URL:-http://localhost:8080}
RABBIT=${RABBIT_URL:-http://localhost:15672}
if [ -z "$TOKEN" ]; then
  echo "Defina TOKEN com um access token (Keycloak local: copie do navegador, ver README): TOKEN=eyJ... $0"
  exit 1
fi
PY=$(command -v python3 || command -v python)
FALHAS=0

ok()    { echo "  [OK]    $*"; }
falha() { echo "  [FALHA] $*"; FALHAS=$((FALHAS + 1)); }
json()  { "$PY" -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

# requisicao METODO CAMINHO [CORPO] -> preenche $STATUS e $CORPO
requisicao() {
  local saida
  saida=$(curl -s -w '\n%{http_code}' -X "$1" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' ${3:+-d "$3"} "$GW$2")
  STATUS=${saida##*$'\n'}
  CORPO=${saida%$'\n'*}
}

echo "Gateway: $GW | usuario: o dono do TOKEN"

echo "0. Gateway exige token nas rotas protegidas"
SEM_TOKEN=$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/carteira")
if [ "$SEM_TOKEN" = 401 ]; then
  ok "GET /api/carteira sem token devolveu 401"
else
  falha "GET /api/carteira sem token devolveu $SEM_TOKEN (esperava 401)"
fi

echo "1. Gateway -> market-data-service"
for _ in $(seq 1 30); do
  requisicao GET /api/cotacoes/BTCUSDT
  [ "$STATUS" = 200 ] && break
  sleep 2
done
if [ "$STATUS" = 200 ]; then
  ok "cotacao BTCUSDT = $(echo "$CORPO" | json "d['preco']")"
else
  falha "GET /api/cotacoes/BTCUSDT devolveu $STATUS (market-data sem preco ou fora do ar)"
fi

echo "2. Gateway -> wallet-service"
requisicao GET /api/carteira
SALDO_INICIAL=$(echo "$CORPO" | json "d['saldo']")
if [ "$STATUS" = 200 ] && [ -n "$SALDO_INICIAL" ]; then
  ok "carteira criada com saldo $SALDO_INICIAL"
else
  falha "GET /api/carteira devolveu $STATUS: $CORPO"
fi

echo "3. Gateway -> trading-service -> wallet-service (validacao de saldo)"
requisicao POST /api/ordens '{"simbolo":"BTCUSDT","tipo":"ALTA","valor":10000,"duracaoSegundos":15}'
if [ "$STATUS" = 422 ] && echo "$CORPO" | grep -q "Saldo insuficiente"; then
  ok "aposta acima do saldo recusada"
else
  falha "esperava 422 'Saldo insuficiente', veio $STATUS: $CORPO"
fi

echo "4. Abre uma rodada (trading consulta preco e saldo)"
requisicao POST /api/ordens '{"simbolo":"BTCUSDT","tipo":"ALTA","valor":100,"duracaoSegundos":15}'
ORDEM_ID=$(echo "$CORPO" | json "d['id']")
if [ "$STATUS" = 201 ] && [ -n "$ORDEM_ID" ]; then
  ok "ordem $ORDEM_ID aberta a $(echo "$CORPO" | json "d['precoEntrada']")"
else
  falha "POST /api/ordens devolveu $STATUS: $CORPO"
  echo; echo "Resultado: $FALHAS falha(s), abortando antes da rodada."; exit 1
fi

echo "5. Rodada resolvida com o preco que chegou pelo RabbitMQ (ate 40s)"
RESULTADO=ABERTA
for _ in $(seq 1 20); do
  sleep 2
  requisicao GET "/api/ordens/$ORDEM_ID"
  RESULTADO=$(echo "$CORPO" | json "d['status']")
  [ "$RESULTADO" != ABERTA ] && break
done
if [ "$RESULTADO" != ABERTA ] && [ -n "$RESULTADO" ]; then
  VALOR_LIQUIDO=$(echo "$CORPO" | json "d['valorLiquido']")
  ok "ordem $RESULTADO, valor liquido $VALOR_LIQUIDO"
else
  falha "ordem continua ABERTA depois de 40s (trading sem preco.atualizado?)"
fi

echo "6. Wallet aplicou ordem.executada recebido pelo RabbitMQ (ate 10s)"
APLICADA=""
for _ in $(seq 1 5); do
  requisicao GET /api/carteira/extrato
  APLICADA=$(echo "$CORPO" | json "next((t['valor'] for t in d if t['referencia']=='$ORDEM_ID'), '')")
  [ -n "$APLICADA" ] && break
  sleep 2
done
requisicao GET /api/carteira
SALDO_FINAL=$(echo "$CORPO" | json "d['saldo']")
ESPERADO=$("$PY" -c "print(float('${SALDO_INICIAL:-0}') + float('${VALOR_LIQUIDO:-0}'))")
if [ -n "$APLICADA" ] && "$PY" -c "import sys; sys.exit(abs(float('$SALDO_FINAL') - $ESPERADO) > 0.001)"; then
  ok "extrato tem a rodada ($APLICADA) e saldo foi de $SALDO_INICIAL para $SALDO_FINAL"
else
  falha "wallet nao aplicou a rodada: movimento='$APLICADA' saldo=$SALDO_FINAL esperado=$ESPERADO"
fi

echo "7. Nenhuma mensagem na fila de erro (DLQ)"
DLQ=$(curl -s -u guest:guest "$RABBIT/api/queues/%2F/wallet.ordem-executada.dlq" | json "d['messages']")
if [ -z "$DLQ" ]; then
  echo "  [PULOU] painel do RabbitMQ indisponivel em $RABBIT"
elif [ "$DLQ" = 0 ]; then
  ok "wallet.ordem-executada.dlq vazia"
else
  falha "wallet.ordem-executada.dlq tem $DLQ mensagem(ns)"
fi

echo
if [ "$FALHAS" -eq 0 ]; then
  echo "Resultado: todas as verificacoes passaram."
else
  echo "Resultado: $FALHAS falha(s)."
  exit 1
fi
