## 1. Introdução
**FinTech Bank - Sistema Bancário Distribuído com Arquitetura de Microsserviços e Padrão Saga**

### Vídeo de Apresentação:

https://github.com/user-attachments/assets/b5edc4e1-f44e-472d-8901-21fb4bc3b6a2

### Diagrama de Domínio:

<img width="762" height="442" alt="Domínio_Banco" src="https://github.com/user-attachments/assets/fadb6958-a14b-46a8-8ad6-b17feb71b52c" />

## 2. Proposta e Tema

- **Problema que o sistema pretende resolver**:  
  Gerenciamento de contas correntes e realização de transferências financeiras entre contas de forma distribuída, garantindo consistência eventual, alta disponibilidade, resiliência a falhas e desacoplamento entre a gestão de saldos e o processamento de transferências.

- **Usuários principais**:  
  - **Clientes Correntistas**: Acessam o Internet Banking para consultar saldos, realizar depósitos, saques e transferências entre contas.  
  - **Administradores / Operadores do Banco**: Acompanham transações, realizam auditoria e inspecionam o ciclo de vida das transações distribuídas.

- **Principais funcionalidades**:  
  - Cadastro de clientes e autenticação via token JWT.
  - Abertura de contas correntes, consulta de saldo, crédito (depósito) e débito (saque).
  - Transferências entre contas orquestradas pelo padrão Saga (com compensação em caso de falha).
  - Extrato detalhado com histórico de transações e status da Saga em tempo real.
  - Frontend moderno de Internet Banking com painel de controle, cartão virtual e ações rápidas.

- **Justificativa para arquitetura de microsserviços**:  
  Operações de consulta e atualização de saldos exigem baixa latência e consistência transacional local (ACID). Já as transferências entre diferentes contas envolvem orquestração assíncrona, tolerância a falhas parciais e conciliação (Saga). A separação em microsserviços permite escalabilidade independente, isolamento de falhas e evolução desacoplada dos domínios bancários.

---

## 3. Desenho e Descrição da Arquitetura

```
               [ Navegador / Cliente ]
                          |
              (HTTP / REST - Porta 3000/5173)
                          v
         +----------------------------------+
         |     FrontEnd (React 19 + MUI)    |
         +----------------------------------+
                          |
                          v
         +----------------------------------+
         |   API Gateway (Spring Cloud)     | <---> [ Eureka Server ]
         |           Porta 8085             |      (Service Discovery)
         +----------------------------------+          Porta 8761
                   /              \
                  /                \
        (Rotas /contas-banco)    (Rotas /transferencias)
                /                    \
               v                      v
    +--------------------+  Saga Sync +-------------------------+
    |   Conta-Service    |<-----------|  Transferencia-Service   |
    |    Porta 8081      |            |       Porta 8082        |
    +--------------------+            +-------------------------+
              |   ^                                |   ^
              |   |       Apache Kafka (9092)      |   |
              +---|--------------------------------+---|
                  |  Eventos: ContaDebitada,           |
                  |  ContaCreditada, DebitoRejeitado   |
```

- **Discovery Server (Eureka)**: Registro e descoberta dinâmica dos microsserviços.
- **API Gateway (Spring Cloud Gateway)**: Ponto único de entrada, roteamento dinâmico, deduplicação de CORS e propagação de headers.
- **Conta-Service**: Domínio de clientes, autenticação JWT, contas, saldos e persistência transacional com Outbox pattern.
- **Transferencia-Service**: Orquestrador do ciclo de vida das transferências (Saga), controlando os estados de débito, crédito e compensação.
- **Apache Kafka**: Barramento de mensageria assíncrona para propagação de eventos de domínio.
- **FrontEnd**: Aplicação SPA em React 19, TypeScript, Material UI 7 e Toolpad Core.

---

## 4. Instruções de Execução

### Execução via Docker Compose (Recomendado)
Para subir todo o ecossistema (Kafka, Eureka, Gateway, Conta-Service, Transferencia-Service e Frontend):

```bash
docker compose up --build
```

### URLs de Acesso
- **Internet Banking (Frontend)**: `http://localhost:3000` (ou `http://localhost:5173`)
- **API Gateway**: `http://localhost:8085`
- **Eureka Server (Dashboard)**: `http://localhost:8761`
- **Conta-Service**: `http://localhost:8081`
- **Transferencia-Service**: `http://localhost:8082`

---

## 5. Discovery Server (Eureka Server)
- **URL de acesso**: `http://localhost:8761`
- **Serviços registrados**:
  - `GATEWAY`
  - `CONTA-SERVICE`
  - `TRANSFERENCIA-SERVICE`

---

## 6. API Gateway
- **URL de acesso**: `http://localhost:8085`
- **Rotas configuradas**:
  - `/login`, `/clientes/**` &rarr; `lb://conta-service`
  - `/contas-banco/**` &rarr; `lb://conta-service`
  - `/transferencias/**` &rarr; `lb://transferencia-service`

---

## 7. Banco Não Relacional e Estratégia de Dados

- **Microservice**: `Transferencia-Service` e Histórico de Eventos / Outbox do `Conta-Service`.
- **Tipo de banco**: Orientado a Documentos / Chave-Valor (ex.: MongoDB / DocumentDB / Redis).
- **Justificativa da escolha**:
  - **Flexibilidade de Esquema**: O histórico de eventos do padrão Saga e as mensagens do Transactional Outbox possuem estruturas dinâmicas que variam conforme o evento (`ContaDebitada`, `ContaCreditada`, `DebitoRejeitado`).
  - **Alta vazão de escrita (Append-Only)**: Logs de auditoria e históricos de eventos raramente sofrem updates, beneficiando-se da velocidade de inserção de bancos NoSQL.
  - **Consulta rápida por chave**: Consultas por `transferenciaId` ou `correlationId` para auditoria e conciliação dispensam joins complexos.

---

## 8. Detalhamento dos Microsserviços

### Microsserviço: `Conta-Service`
- **Responsabilidade**: Gerenciamento do ciclo de vida dos correntistas, autenticação JWT, abertura de contas, manutenção atômica de saldos e controle de concorrência.
- **Principais entidades**: `Cliente`, `Conta`, `ContaHistorico`, `ChaveIdempotencia`, `ContaOutboxMessage`.
- **Principais endpoints**:
  - `POST /login`: Autenticação e emissão de token JWT.
  - `POST /clientes/registrar`: Cadastro de novos correntistas.
  - `GET /contas-banco/listar`: Listagem de contas correntes.
  - `GET /contas-banco/listar/{id}`: Consulta de conta por ID.
  - `POST /contas-banco/adicionar`: Abertura de conta corrente com saldo inicial.
  - `PUT /contas-banco/creditar/{id}/{saldo}`: Depósito em conta.
  - `PUT /contas-banco/debitar/{id}/{saldo}`: Saque / débito com validação de saldo.
  - `DELETE /contas-banco/delete/{id}`: Encerramento de conta.
- **Banco de dados**: Relacional (H2 / MySQL) com controle de versão otimista (`@Version`).
- **Justificativa de separação**: Isolar as regras de negócio de saldo e autorização em um serviço de alta consistência transacional (ACID).

---

### Microsserviço: `Transferencia-Service`
- **Responsabilidade**: Gerenciamento do ciclo de vida da transferência financeira distribuída (Saga), registro do estado da transação e emissão de comandos de débito, crédito e compensação.
- **Principais entidades**: `Transferencia` (com estados: `INICIADA`, `CONTA_ORIGEM_DEBITADA`, `DEBITO_FALHOU`, `CONCLUIDA`, `CREDITO_FALHOU`, `COMPENSADA`), `OutboxMessage`.
- **Principais endpoints**:
  - `POST /transferencias`: Inicia uma transferência entre duas contas.
  - `GET /transferencias`: Lista todas as transferências realizadas.
  - `GET /transferencias/{id}`: Consulta o status da transferência por ID.
- **Banco de dados**: Relacional / Documental para persistência de transações e logs de outbox.
- **Justificativa de separação**: Isolar a lógica de orquestração de transações distribuídas, garantindo que falhas em transferências não impactem a operação normal de consulta de contas e saldos.

---

## 9. Comunicação Inter-serviços, Falhas e Resiliência

- **Comunicação entre serviços**:
  - O `Transferencia-Service` chama o `Conta-Service` via HTTP (REST com `ContaCommandClient`) para iniciar o débito imediato na conta de origem com chave de idempotência.
  - A continuação do fluxo ocorre via mensageria assíncrona com **Apache Kafka**, onde o `TransferenciaProcessManager` consome eventos (`ContaDebitada`, `ContaCreditada`, `DebitoRejeitado`) e orquestra a conclusão ou compensação da Saga.

- **Problemas de falha possíveis**:
  - Indisponibilidade de rede ou lentidão no `Conta-Service`.
  - Tentativa de débito com saldo insuficiente na conta de origem.
  - Falha após o débito na origem, antes da conclusão do crédito no destino.

- **Mecanismos de resiliência implementados**:
  1. **Padrão Saga**: Máquina de estados com rastreamento de cada etapa e acionamento de transações de compensação (estorno) caso o crédito falhe.
  2. **Chave de Idempotência (`X-Idempotency-Key`)**: Garante que requisições repetidas não causem débitos duplicados.
  3. **Transactional Outbox Pattern**: Evita o problema de dual-write salvando o evento na mesma transação de banco antes da publicação no Kafka.
  4. **Controle de Concorrência Otimista**: Campo `@Version` na entidade `Conta` para impedir perda de atualizações concorrentes.

- **Comportamento em caso de falha**:
  - Se o `Conta-Service` recusar o débito (ex.: saldo insuficiente), o `Transferencia-Service` marca o status como `DEBITO_FALHOU` e encerra a transação sem alterar saldos.
  - Se o crédito no destino falhar após o débito ter ocorrido, a Saga aciona o estorno automático na conta de origem, transacionando o status para `COMPENSADA`.

- **Como testar/simular a falha**:
  - **Saldo Insuficiente**: No Frontend ou via REST, tente transferir um valor superior ao saldo da conta de origem. A resposta apresentará erro de saldo insuficiente e a transferência será registrada como `DEBITO_FALHOU`.
  - **Indisponibilidade do Serviço**: Pare o container do serviço de contas (`docker stop conta-service`) e tente executar uma transferência. O `Transferencia-Service` identificará a falha de comunicação e registrará a falha imediatamente, mantendo a integridade do sistema.

---

## 10. Avaliação de Arquitetura Orientada a Eventos (Quarta Entrega)

- **Vantagens (Prós)**:
  - **Desacoplamento Temporal e Espacial**: Produtores e consumidores não precisam estar disponíveis simultaneamente.
  - **Escalabilidade e Vazão**: Processamento assíncrono distribuído sem bloqueio de threads de I/O no cliente.
  - **Auditabilidade e Event Sourcing**: Histórico imutável de todos os acontecimentos do domínio bancário.

- **Desvantagens (Contras)**:
  - **Complexidade Operacional**: Necessidade de manter e monitorar clusters de mensageria (Kafka/Zookeeper/KRaft).
  - **Consistência Eventual**: Leituras logo após uma operação assíncrona podem requerer atualização reativa ou polling enquanto o evento é propagado.
  - **Dificuldade de Depuração**: Necessidade de correlation IDs e rastreamento distribuído através de múltiplos serviços.

- **Cenários mais vantajosos**:
  - Sistemas bancários com orquestração de pagamentos e compensações (Saga).
  - Ambientes com picos de acessos e necessidade de nivelamento de carga (load leveling).
  - Plataformas de auditoria, conciliação financeira e streaming de eventos analíticos em tempo real.
