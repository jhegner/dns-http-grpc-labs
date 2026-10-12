# Guia AWS: Cloud Map + Route 53 + ECS para descoberta de serviços com HTTP e gRPC

> Complemento do guia `guia-dns-http-grpc.md`. Aquele documento explica **como a JVM e as conexões se comportam**. Este explica **o que a AWS acrescenta no caminho** e como dimensionar tudo junto.

---

## 1. Visão geral: o caminho do nome até a conexão

```
┌────────────┐   ┌────────────┐   ┌─────────────────┐   ┌──────────────┐   ┌────────────┐   ┌──────────┐
│ Task ECS   │──►│ Cloud Map  │──►│ Route 53        │──►│ Resolver da  │──►│ Cache da   │──►│ Conexão  │
│ sobe/para  │   │ (des)regis-│   │ hosted zone     │   │ VPC (.2)     │   │ JVM        │   │ HTTP/gRPC│
│            │   │ tra o IP   │   │ privada, TTL    │   │ respeita TTL │   │ TTL 5s     │   │ maxAge   │
└────────────┘   └────────────┘   └─────────────────┘   └──────────────┘   └────────────┘   └──────────┘
```

Comparado com o teste local (Podman), existe uma camada a mais: **o TTL do registro DNS**. Os atrasos de cada camada se somam.

| Camada | Quem controla | Parâmetro |
|---|---|---|
| Registro/remoção do IP | ECS + Cloud Map | Health check, ciclo de vida da task |
| Validade do registro | Você (Cloud Map) | `TTL` do `DnsConfig` |
| Cache na aplicação | Você (JVM) | `networkaddress.cache.ttl` |
| Vida da conexão | Você (servidor/cliente) | `maxConnectionAge`, `timeToLive` |

---

## 2. Quanto tempo leva para o cliente perceber a mudança

### 2.1 Scale up (nova task sobe)

```
Task inicia → fica saudável → é registrada no Cloud Map
   → registro novo é publicado no DNS
   → clientes só o veem quando o cache (registro + JVM) expirar
   → e só o USAM quando abrirem uma conexão nova (maxConnectionAge / maxLifeTime)
```

**Estimativa:**

```
tempo ≈ tempo para ficar saudável + TTL do registro + TTL da JVM + vida máxima da conexão
```

### 2.2 Scale down (task é removida)

```
ECS decide parar a task → task é deregistrada do Cloud Map
   → registro some do DNS
   → clientes continuam com o IP em cache por até (TTL do registro + TTL da JVM)
   → a task precisa continuar atendendo durante esse intervalo
```

> ⚠️ Esta é a parte mais perigosa. Se o processo morrer assim que recebe SIGTERM, os clientes que ainda têm o IP em cache recebem erro (`UNAVAILABLE`, `Connection refused`, `Connection reset`).

### 2.3 Exemplo numérico

| Parâmetro | Valor |
|---|---|
| TTL do registro (Cloud Map) | 10s |
| TTL da JVM | 5s |
| `maxConnectionAge` / `maxLifeTime` | 30s |
| **Scale up: pior caso para tráfego chegar** | ~45s (+ tempo de ficar saudável) |
| **Scale down: delay mínimo antes do shutdown** | ≥ 10 + 5 + margem = **~20s** |

---

## 3. AWS Cloud Map

### 3.1 Tipos de namespace

| Tipo | O que cria | Serve para este guia? |
|---|---|---|
| **DNS privado** (`DNS_PRIVATE`) | Hosted zone privada no Route 53 | ✅ Sim, é o cenário principal |
| DNS público (`DNS_PUBLIC`) | Hosted zone pública | Só para serviços expostos publicamente |
| API only (HTTP) | Nenhum registro DNS; descoberta via API `DiscoverInstances` | ❌ Não gera nome DNS (veja seção 9) |

O nome resultante tem o formato `<serviço>.<namespace>`. Exemplo: `backend.prod.local`.

### 3.2 Política de roteamento: use `MULTIVALUE`

| Política | O que o DNS devolve | Efeito no cliente |
|---|---|---|
| **`MULTIVALUE`** | Até **8** registros saudáveis por resposta | ✅ `getAllByName` retorna vários IPs; `round_robin` do gRPC funciona |
| `WEIGHTED` | **Um** registro por resposta, escolhido por peso | ❌ gRPC com `round_robin` enxerga um IP só |

Consequências do limite de 8:

- Com **mais de 8 tasks**, cada resolução devolve um subconjunto. A re-resolução periódica (TTL curto + `maxConnectionAge`) ajuda a cobrir as demais ao longo do tempo.
- Se você precisa que todas as instâncias sejam conhecidas pelo cliente ao mesmo tempo, considere Service Connect ou a API `DiscoverInstances`.

### 3.3 Tipo de registro: A × SRV

| Modo de rede da task | Registro suportado | Resolvido pelo gRPC/JDK? |
|---|---|---|
| **`awsvpc`** (obrigatório no Fargate) | **A** (ou SRV) | ✅ Sim, com A |
| `bridge` / `host` | **SRV** apenas | ❌ Não por padrão (o JDK e o resolvedor padrão do gRPC leem A/AAAA) |

> Use `awsvpc` e registro do tipo **A**.

### 3.4 TTL do registro

O TTL é definido no `DnsConfig` do serviço do Cloud Map. É ele que o Resolver da VPC respeita antes de consultar a zona de novo.

| TTL do registro | Convergência | Carga no DNS |
|---|---|---|
| 60s (comum por padrão em exemplos) | Lenta | Baixa |
| **10s** | Boa (ponto de partida) | Moderada |
| 1 a 5s | Rápida | Alta (veja limites do Resolver na seção 4.3) |

### 3.5 Health check

| Ponto | Detalhe |
|---|---|
| Namespace DNS **privado** | Health checks do **Route 53 não são suportados** |
| Alternativa | Health check **customizado** do Cloud Map, atualizado pelo **ECS** com base no health check do container |
| Efeito | Cloud Map só devolve instâncias marcadas como saudáveis |
| Atraso | Existe um intervalo entre a task adoecer e sair do DNS. Mantenha **retry** no cliente |

> Por isso o health check **do container** na task definition (seção 5.3) é essencial. Sem ele, o ECS considera a task saudável só por estar em execução.

---

## 4. Route 53 e VPC

### 4.1 Pré-requisitos da VPC

Para a hosted zone privada resolver dentro da VPC:

- `enableDnsSupport = true`
- `enableDnsHostnames = true`

### 4.2 Várias VPCs / várias contas

| Cenário | Como resolver |
|---|---|
| Outra VPC precisa resolver o nome | Associar a hosted zone privada à outra VPC |
| Outra conta | Compartilhamento de namespaces do Cloud Map via **AWS RAM**, ou associação da zona entre contas |
| Rede on-premises | Route 53 Resolver inbound/outbound endpoints |

### 4.3 Limites do Resolver

O Resolver da VPC (endereço base da VPC + 2) tem um limite de consultas por segundo **por interface de rede** (a documentação da AWS cita 1024 pacotes por segundo por ENI; confirme o valor atual).

| Fator | Efeito |
|---|---|
| TTL muito baixo | Mais consultas |
| Muitas tasks no mesmo host (EC2) | Compartilham a mesma ENI do host |
| Fargate | Cada task tem a própria ENI, o que reduz o risco |
| Sintoma de estouro | `UnknownHostException` intermitente, latência alta em resolução |

**Mitigação:** não use TTL abaixo de ~5s sem medir, e acompanhe métricas do Resolver e logs de consulta.

### 4.4 Cache negativo

Quando um nome deixa de existir (serviço sem instâncias), o resultado negativo é guardado conforme o SOA da zona. Na JVM, `networkaddress.cache.negative.ttl` também atua. Mantenha-o baixo (0 a 5s) para que o cliente se recupere logo quando as instâncias voltarem.

---

## 5. Amazon ECS

### 5.1 Configuração do serviço

O serviço ECS é associado ao serviço do Cloud Map por `serviceRegistries`:

```json
{
  "serviceName": "backend",
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-aaa", "subnet-bbb"],
      "securityGroups": ["sg-xxxx"]
    }
  },
  "serviceRegistries": [
    { "registryArn": "arn:aws:servicediscovery:us-east-1:123456789012:service/srv-xxxxxxxx" }
  ],
  "deploymentConfiguration": {
    "minimumHealthyPercent": 100,
    "maximumPercent": 200
  }
}
```

> Com `awsvpc` e registro A, basta o `registryArn`. Não informe `containerName`/`containerPort` (usados com SRV).

### 5.2 Deploys (rolling update)

Durante um deploy, tasks antigas saem e novas entram. Cada saída é um **scale down** da seção 2.2.

| Parâmetro | Recomendação |
|---|---|
| `minimumHealthyPercent` | 100 (mantém capacidade durante o deploy) |
| `maximumPercent` | 200 (sobe novas antes de derrubar antigas) |
| Desligamento da task antiga | Com delay + drenagem (seção 5.4) |

### 5.3 Task definition

```json
{
  "containerDefinitions": [
    {
      "name": "backend",
      "image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/backend:1.0.0",
      "essential": true,
      "stopTimeout": 60,
      "portMappings": [{ "containerPort": 9090, "protocol": "tcp" }],
      "healthCheck": {
        "command": ["CMD-SHELL", "grpc_health_probe -addr=localhost:9090 || exit 1"],
        "interval": 10,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 30
      },
      "environment": [
        { "name": "JAVA_TOOL_OPTIONS", "value": "-Dsun.net.inetaddr.ttl=5 -Dnetworkaddress.cache.ttl=5" },
        { "name": "SHUTDOWN_DELAY_SECONDS", "value": "20" }
      ]
    }
  ]
}
```

Notas:

- O `healthCheck` do container alimenta o status no Cloud Map. Para HTTP use `curl -f http://localhost:8080/actuator/health/readiness`. Para gRPC use o protocolo `grpc.health.v1` (por exemplo com `grpc_health_probe` incluído na imagem).
- O `stopTimeout` (tempo entre SIGTERM e SIGKILL) tem padrão de 30s e máximo de 120s no Fargate. Dimensione conforme a seção 5.4.

### 5.4 Desligamento correto: **o ECS não tem `preStop`**

No Kubernetes você usaria um hook `preStop` com `sleep`. No ECS **não existe esse hook**, então o atraso precisa estar **dentro da aplicação**, logo ao receber o SIGTERM.

**Sequência desejada:**

```
SIGTERM
  │
  ├─ (1) aguarda SHUTDOWN_DELAY (ex.: 20s)  ← continua atendendo; DNS e caches expiram
  ├─ (2) server.shutdown()                  ← para de aceitar novas RPCs, envia GOAWAY
  ├─ (3) drena requisições em andamento     ← awaitTermination (ex.: 20s)
  └─ (4) encerra
```

**Dimensionamento:**

```
SHUTDOWN_DELAY  ≥  TTL do registro + TTL da JVM + margem
stopTimeout     ≥  SHUTDOWN_DELAY + tempo de drenagem + margem
```

Exemplo: `10 + 5 + 5 = 20s` de delay, `20s` de drenagem, `stopTimeout = 60s`.

**gRPC (servidor):**

```java
long delay = Long.parseLong(System.getenv().getOrDefault("SHUTDOWN_DELAY_SECONDS", "20"));

Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try {
        Thread.sleep(TimeUnit.SECONDS.toMillis(delay));       // (1) deixa o DNS "esvaziar"
        server.shutdown();                                    // (2)
        if (!server.awaitTermination(20, TimeUnit.SECONDS)) { // (3)
            server.shutdownNow();
        }
    } catch (InterruptedException e) {
        server.shutdownNow();
    }
}));
```

**HTTP (Spring Boot):** o shutdown gracioso do web server é iniciado depois do `ContextClosedEvent`. Um listener que dorme antes disso cria o atraso:

```java
@Component
public class ShutdownDelay implements ApplicationListener<ContextClosedEvent> {

    @Value("${shutdown.delay-seconds:20}")
    private long delaySeconds;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(delaySeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```

> Valide no seu ambiente que o atraso acontece antes de o Tomcat parar de aceitar conexões (comportamento pode variar entre versões do Spring Boot).

---

## 6. Resumo da configuração no código (referência rápida)

Os detalhes estão no `guia-dns-http-grpc.md`. Na AWS, só mudam os valores e o nome do alvo:

| Item | Valor sugerido na AWS |
|---|---|
| Alvo gRPC | `dns:///backend.prod.local:9090` |
| URL HTTP | `http://backend.prod.local:8080` |
| `networkaddress.cache.ttl` (cliente) | 5s |
| TTL do registro (Cloud Map) | 10s |
| gRPC `maxConnectionAge` (servidor) | 30 a 60s |
| HTTP pool `timeToLive`/`maxLifeTime` (cliente) | 30s |
| Política de balanceamento gRPC | `round_robin` |
| Retry de `UNAVAILABLE` (idempotente) | Sim, 2 a 3 tentativas |
| `SHUTDOWN_DELAY_SECONDS` (servidor) | ≥ 20s |

---

## 7. Criando a infraestrutura (AWS CLI)

**Namespace DNS privado:**

```bash
aws servicediscovery create-private-dns-namespace \
  --name prod.local \
  --vpc vpc-0123456789abcdef0
```

**Serviço do Cloud Map (registro A, MULTIVALUE, TTL 10s):**

```bash
aws servicediscovery create-service \
  --name backend \
  --namespace-id ns-xxxxxxxxxxxx \
  --dns-config "NamespaceId=ns-xxxxxxxxxxxx,RoutingPolicy=MULTIVALUE,DnsRecords=[{Type=A,TTL=10}]" \
  --health-check-custom-config FailureThreshold=1
```

**Criar o serviço ECS ligado ao Cloud Map:**

```bash
aws ecs create-service \
  --cluster meu-cluster \
  --service-name backend \
  --task-definition backend:1 \
  --desired-count 3 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-aaa,subnet-bbb],securityGroups=[sg-xxxx]}" \
  --service-registries "registryArn=arn:aws:servicediscovery:us-east-1:123456789012:service/srv-xxxxxxxx"
```

> Os comandos são um ponto de partida. Em projetos reais use Terraform, CloudFormation ou CDK. Confira a sintaxe vigente na documentação da AWS.

**Security groups:** libere a porta do serviço (`9090` ou `8080`) do security group do cliente para o security group do backend.

---

## 8. Validação na AWS

### 8.1 Passo a passo

| Objetivo | Como |
|---|---|
| Ver as instâncias registradas | `aws servicediscovery list-instances --service-id srv-xxxx` |
| Ver apenas as saudáveis | `aws servicediscovery discover-instances --namespace-name prod.local --service-name backend` (disponível para namespaces com API, inclusive DNS) |
| Ver o que o DNS devolve **de dentro** da VPC | ECS Exec no cliente: `getent ahosts backend.prod.local` |
| Ver o que a JVM enxerga | Endpoint de teste com `InetAddress.getAllByName(...)` |
| Distribuição entre réplicas | Chamar em loop e contar por hostname (como no teste local) |
| Consultas DNS | Habilitar **Route 53 Resolver query logging** na VPC |

### 8.2 Roteiro de teste

1. Suba 3 tasks do backend e confirme os 3 IPs com `getent ahosts`.
2. Execute o loop de chamadas no cliente.
3. **Scale up** para 5: `aws ecs update-service --cluster meu-cluster --service backend --desired-count 5`. Cronometre até as novas tasks aparecerem na distribuição.
4. **Scale down** para 2. Confirme que **não há erros** durante o período e que as tasks removidas deixam de receber tráfego.
5. Faça um **deploy** (nova revisão da task definition) com tráfego contínuo e verifique a taxa de erros.
6. Compare os tempos medidos com a estimativa da seção 2.

### 8.3 Limitação do teste local

O DNS do Podman não reproduz o TTL do Route 53. Para simular localmente, coloque um CoreDNS ou `dnsmasq` no compose com TTL controlado e aponte o `app` para ele. Para validar de forma fiel, use um ambiente real na AWS.

---

## 9. Alternativas e quando considerá-las

| Alternativa | Como funciona | Quando considerar |
|---|---|---|
| **ECS Service Connect** | Proxy (Envoy) gerenciado em cada task faz balanceamento e detecção de instâncias com problema; o cliente fala com o proxy local | Comunicação entre serviços ECS sem querer lidar com TTL de DNS e `maxConnectionAge` |
| **API `DiscoverInstances`** (Cloud Map) | O cliente consulta a API, com filtros por atributo e saúde, e monta a lista de endpoints | Mais de 8 instâncias, ou necessidade de metadados; exige código extra no cliente |
| **ALB** (HTTP ou gRPC/HTTP2) | Cliente fala com um endpoint único; o ALB distribui entre os alvos com `deregistration delay` | Quando um balanceador central é aceitável; perde-se o balanceamento do lado do cliente |
| **NLB** | Balanceamento L4 | gRPC sem inspeção L7; o cliente enxerga um IP/nome único |

> Se existe um balanceador ou proxy entre cliente e servidor, o cliente enxerga apenas o IP dele. Nesse caso, DNS curto e `round_robin` no cliente deixam de ser a peça principal; passam a valer as configurações do balanceador (idle timeout, deregistration delay, etc.).

---

## 10. Nota sobre EKS (Kubernetes)

Se o seu cenário for EKS e não ECS, o princípio é o mesmo, mas os componentes mudam:

| Tema | Detalhe |
|---|---|
| Descoberta | Service **headless** (`clusterIP: None`) devolve o IP de cada pod |
| Cache extra | **CoreDNS** tem plugin `cache` (padrão de 30s); ajuste o TTL máximo se necessário |
| Hook de desligamento | Existe `preStop` (use `sleep` curto) e `terminationGracePeriodSeconds` |
| `ndots` | O padrão `ndots:5` do pod gera várias consultas por nome; use nomes totalmente qualificados (terminando em ponto) para reduzir |
| Carga de DNS | **NodeLocal DNSCache** reduz consultas ao CoreDNS |

---

## 11. Armadilhas específicas da AWS

| # | Armadilha | Sintoma | Correção |
|---|---|---|---|
| 1 | Política `WEIGHTED` | gRPC só usa um IP | Usar `MULTIVALUE` |
| 2 | Modo `bridge` com SRV | `UnknownHostException` ou nome sem IP | Usar `awsvpc` + registro A |
| 3 | TTL do registro em 60s | Convergência lenta mesmo com TTL de 5s na JVM | Reduzir para ~10s |
| 4 | Sem health check do container | Task doente permanece no DNS | Definir `healthCheck` na task definition |
| 5 | Processo morre logo no SIGTERM | Erros durante scale down e deploy | Delay no shutdown + `stopTimeout` adequado |
| 6 | `stopTimeout` menor que delay + drenagem | SIGKILL no meio da drenagem | Recalcular (seção 5.4) |
| 7 | Mais de 8 instâncias | Alguns clientes não conhecem todas | Re-resolução periódica, `DiscoverInstances` ou Service Connect |
| 8 | TTL agressivo (1s) com muitas tasks | `UnknownHostException` intermitente | Subir o TTL e monitorar o Resolver |
| 9 | Hosted zone sem DNS support na VPC | Nome não resolve | Habilitar `enableDnsSupport` e `enableDnsHostnames` |
| 10 | Security group sem regra cliente → backend | Timeout de conexão | Liberar a porta entre os security groups |
| 11 | JVM com TTL `-1` | Cliente nunca vê mudanças | Definir 5s e no lugar certo (Security para HTTP, System para gRPC) |

---

## 12. Checklist final

**Infraestrutura (Cloud Map / Route 53 / ECS)**

- [ ] Namespace DNS privado criado e associado à VPC (DNS support e hostnames ativos)
- [ ] Serviço do Cloud Map com **`MULTIVALUE`**, registro **A**, **TTL ~10s**
- [ ] Task em **`awsvpc`**
- [ ] `healthCheck` do container definido (readiness real, não só "processo vivo")
- [ ] `minimumHealthyPercent=100` e `maximumPercent=200` para deploys
- [ ] `stopTimeout` ≥ delay de shutdown + drenagem
- [ ] Security groups liberando cliente → backend

**Servidor**

- [ ] `SHUTDOWN_DELAY` ≥ TTL do registro + TTL da JVM + margem
- [ ] Desligamento gracioso com `awaitTermination`
- [ ] gRPC: `maxConnectionAge` (30 a 60s) e `maxConnectionAgeGrace`
- [ ] HTTP: `keep-alive-timeout` e `max-keep-alive-requests`

**Cliente**

- [ ] `networkaddress.cache.ttl=5` (Security para HTTP, System para gRPC), definido antes da primeira resolução
- [ ] gRPC: `dns:///`, `round_robin`, keepalive
- [ ] HTTP: vida máxima da conexão no pool
- [ ] Retry limitado para operações idempotentes
- [ ] Timeouts configurados

**Validação**

- [ ] Scale up, scale down e deploy testados com tráfego contínuo
- [ ] Tempo de convergência medido e comparado com a estimativa
- [ ] Taxa de erros durante scale down e deploy próxima de zero

---

### Ideia para guardar

> **Na AWS, o DNS tem mais um relógio: o TTL do registro.**
> Some os relógios (registro + JVM + conexão) para estimar a convergência, e faça o servidor **esperar essa soma** antes de morrer.
