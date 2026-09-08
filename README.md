# api-gateway

Parte do projeto **Tigrinho Trader** (disciplina Projeto de Software).

## Finalidade

Porta de entrada unica da aplicacao (Spring Cloud Gateway). Valida o token JWT emitido pelo Auth0 (OAuth2) antes de rotear a chamada pro servico correto. E o unico ponto que o frontend enxerga; nenhum servico interno e exposto direto.

## Como interage com os outros servicos

- Roteia (sincrono, REST) para trading-service, wallet-service, market-data-service e notification-service.
- Valida JWT do Auth0 em toda requisicao antes de rotear.

## Repositorios do projeto

- [trading-service](https://github.com/tigrinho-trader/trading-service)
- [wallet-service](https://github.com/tigrinho-trader/wallet-service)
- [market-data-service](https://github.com/tigrinho-trader/market-data-service)
- [notification-service](https://github.com/tigrinho-trader/notification-service)
- [frontend](https://github.com/tigrinho-trader/frontend)
- [docs-arquitetura](https://github.com/tigrinho-trader/docs-arquitetura)
