# api-gateway

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Porta de entrada unica da aplicacao (Spring Cloud Gateway). Valida o token JWT emitido pelo Auth0 (OAuth2) antes de rotear a chamada pro servico correto. E o unico ponto que o frontend enxerga; nenhum servico interno e exposto direto.

## Como interage com os outros servicos

- Roteia (sincrono, REST) para trading-service, wallet-service, market-data-service e notification-service.
- Valida JWT do Auth0 em toda requisicao antes de rotear.

## Rotas

| Caminho publico | Servico | Caminho interno |
|---|---|---|
| `/api/ordens/**` | trading-service (8081) | `/ordens/**` |
| `/api/carteira/**` | wallet-service (8082) | `/carteira/**` |
| `/api/cotacoes/**` | market-data-service (8083) | `/cotacoes/**` |
| `/api/notificacoes/**` | notification-service (8084) | `/notificacoes/**` |

Enderecos configuraveis por `TRADING_URL`, `WALLET_URL`, `MARKET_DATA_URL`, `NOTIFICATION_URL`; origem do frontend liberada no CORS por `FRONTEND_ORIGINS`.

> Validacao do JWT (Auth0) entra na Etapa 3. Ate la o cliente manda `X-Usuario-Id` direto; depois o gateway
> passa a preencher esse cabecalho a partir do `sub` do token e descartar o que vier do cliente.

## Subindo a aplicacao inteira

Com os repos clonados lado a lado (`api-gateway`, `trading-service`, `wallet-service`, `market-data-service`):

```bash
docker compose up --build
# sem acesso a Binance:
SIMULADOR=true BINANCE=false docker compose up --build
```

Exemplo de uma rodada:

```bash
curl localhost:8080/api/cotacoes
curl -H "X-Usuario-Id: ana" localhost:8080/api/carteira
curl -H "X-Usuario-Id: ana" -H "Content-Type: application/json" \
     -d '{"simbolo":"BTCUSDT","tipo":"ALTA","valor":100,"duracaoSegundos":15}' localhost:8080/api/ordens
# ~15s depois
curl -H "X-Usuario-Id: ana" localhost:8080/api/ordens
curl -H "X-Usuario-Id: ana" localhost:8080/api/carteira/extrato
```

Painel do RabbitMQ: http://localhost:15672 (guest/guest).

## Repositorios do projeto

- [trading-service](https://github.com/tigrinho-trader/trading-service)
- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
