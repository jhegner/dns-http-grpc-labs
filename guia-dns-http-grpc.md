# Guia didático: DNS, cache da JVM e balanceamento com HTTP e gRPC

> Objetivo: garantir que uma aplicação Java/Spring Boot **enxergue mudanças no número de réplicas** (scale up/down) de um serviço downstream em poucos segundos, sem ficar presa a IPs antigos.

---

## 1. O problema em uma frase

> **Resolver o DNS de novo não basta. É preciso também abrir conexões novas.**

Para a mudança de réplicas chegar até o tráfego, **três camadas** precisam cooperar:

```
┌──────────────────────────────────────────────────────────────┐
│ 1. DNS do ambiente   → devolve a lista atual de IPs          │
│ 2. Cache da JVM      → por quanto tempo a lista é reaproveitada │
│ 3. Conexões abertas  → continuam usando o IP antigo até fechar │
└──────────────────────────────────────────────────────────────┘
```

Se **qualquer** uma das camadas for ignorada, o cliente continua falando com IPs que não existem mais (ou nunca fala com os novos).

---

## 2. Conceitos fundamentais

### 2.1 DNS com vários IPs (round robin de DNS)

Um mesmo nome (`backend`) pode apontar para **vários IPs**, um por réplica:

```
backend  →  10.89.0.5, 10.89.0.6, 10.89.0.7
```

| Ambiente | Como o nome devolve todos os IPs |
|---|---|
| Podman/Docker Compose | DNS embutido da rede (aardvark-dns / DNS do Docker) devolve o IP de cada réplica |
| Kubernetes | Service **headless** (`clusterIP: None`) devolve o IP de cada pod. Um Service comum devolve um IP virtual único |
| Docker Swarm | Usar `endpoint_mode: dnsrr` para devolver todos os IPs |

> ⚠️ Se o nome devolve **um único IP** (VIP de um Service comum, load balancer, proxy), a mudança de réplicas acontece atrás desse IP e este guia deixa de ser necessário no cliente.

### 2.2 TTL e cache da JVM

A JVM guarda o resultado de uma consulta DNS em memória por um tempo.

| Situação | Comportamento |
|---|---|
| Sem configuração e sem SecurityManager | Cache de **30 segundos** |
| Com SecurityManager (legado) | Cache **para sempre** |
| `networkaddress.cache.ttl = 5` | Cache de **5 segundos** |
| `networkaddress.cache.ttl = -1` | Cache **eterno** (armadilha clássica) |
| `networkaddress.cache.ttl = 0` | **Sem cache** (cada chamada consulta o DNS) |

Existe também o cache **negativo** (`networkaddress.cache.negative.ttl`, padrão 10s) para nomes que falharam.

### 2.3 Dois lugares diferentes para configurar

Isso causa muita confusão:

| Quem lê | Onde configurar | Exemplo |
|---|---|---|
| `InetAddress` do JDK (HttpURLConnection, Apache HttpClient, JDK HttpClient, OkHttp) | **Security property** (arquivo `java.security` ou `Security.setProperty`) ou a flag legada `-Dsun.net.inetaddr.ttl` | `Security.setProperty("networkaddress.cache.ttl", "5")` |
| **Resolvedor DNS do gRPC** (`DnsNameResolver`) | **System property** | `-Dnetworkaddress.cache.ttl=5` |

> Configure o TTL **antes** de qualquer resolução de nome (primeira linha do `main`, ou via flags da JVM). Depois que a classe já leu o valor, mudar não tem efeito.

**Forma mais segura em container (cobre os dois casos):**

```bash
JAVA_TOOL_OPTIONS="-Dsun.net.inetaddr.ttl=5 -Dnetworkaddress.cache.ttl=5"
```

### 2.4 Conexões persistentes (keep-alive)

O TTL só afeta **novas** resoluções. Quem já tem uma conexão aberta não pergunta ao DNS de novo.

```
Cliente ──(conexão aberta há 2 horas)──► 10.89.0.5   ← réplica antiga
              ▲
              └─ DNS já diz que existem .5, .6, .7, .8... mas ninguém consulta
```

| Protocolo | Natureza da conexão | Consequência |
|---|---|---|
| HTTP/1.1 com keep-alive | Reaproveitada em pool | Fica no IP antigo até expirar ou fechar |
| HTTP/2 / **gRPC** | **Uma** conexão multiplexada de longa duração | Fica no IP antigo praticamente para sempre |

### 2.5 Quem decide renovar a conexão?

| Lado | Mecanismo |
|---|---|
| **Cliente** | Tempo máximo de vida da conexão (`timeToLive`, `maxLifeTime`) |
| **Servidor** | Encerra a conexão depois de um tempo ou número de requisições (`Connection: close`, `GOAWAY`, `maxConnectionAge`) |

**Regra de ouro:** configure o **servidor** para renovar conexões. Ele é o único que você sempre controla e que protege **todos** os clientes, inclusive os mal configurados.

### 2.6 Tempo efetivo de convergência

```
tempo para enxergar a mudança  ≈  max( vida máxima da conexão , TTL do DNS )
```

Exemplo: `maxConnectionAge=30s` e `TTL=5s` → ~30s para enxergar réplicas novas.

---

## 3. HTTP (REST)

### 3.1 O que acontece

1. O cliente resolve `backend` → recebe vários IPs.
2. A maioria dos clientes tenta **o primeiro IP** da lista (o DNS embaralha a ordem a cada resposta, e é isso que "balanceia").
3. A conexão fica no pool (keep-alive) e é reutilizada.
4. Novas conexões só nascem quando o pool precisa crescer ou a conexão expira.

> Importante: o balanceamento via DNS no HTTP é **estatístico**, por conexão e não por requisição. Com poucas conexões no pool, a distribuição pode ser desigual.

### 3.2 Configuração no SERVER (HTTP)

Objetivo: **fechar conexões periodicamente** para empurrar os clientes de volta ao DNS.

**Spring Boot (Tomcat embarcado):**

```properties
# Fecha conexão ociosa após 20s
server.tomcat.keep-alive-timeout=20s
# Fecha a conexão após N requisições (padrão: 100). Reduza se o tráfego for alto
server.tomcat.max-keep-alive-requests=100

# Desligamento gracioso: termina as requisições em andamento antes de parar
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

**Nginx (se for o servidor/proxy):**

```nginx
keepalive_timeout  20s;
keepalive_requests 100;
keepalive_time     60s;   # vida máxima da conexão (nginx >= 1.19.10)
```

**Boas práticas no servidor:**

- No desligamento, **deixe de aceitar tráfego antes de morrer** (readiness falha → sai do DNS → drena → encerra).
- Em Kubernetes: `preStop` com `sleep` curto + `terminationGracePeriodSeconds` maior que o tempo de drenagem.

### 3.3 Configuração no CLIENT (HTTP)

**Passo 1: TTL do DNS (início do `main`):**

```java
public static void main(String[] args) {
    Security.setProperty("networkaddress.cache.ttl", "5");
    Security.setProperty("networkaddress.cache.negative.ttl", "0");
    SpringApplication.run(App.class, args);
}
```

**Passo 2: vida máxima da conexão no pool (depende do cliente HTTP):**

| Cliente | Configuração |
|---|---|
| **Apache HttpClient 5** | `ConnectionConfig.custom().setTimeToLive(TimeValue.ofSeconds(30))` no `PoolingHttpClientConnectionManagerBuilder` |
| **Reactor Netty (WebClient)** | `ConnectionProvider.builder("pool").maxLifeTime(Duration.ofSeconds(30)).maxIdleTime(Duration.ofSeconds(20)).build()` |
| **OkHttp** | `new ConnectionPool(maxIdle, 20, TimeUnit.SECONDS)` (tempo ocioso) e, se possível, `Connection: close` do lado servidor |
| **JDK HttpClient** | `-Djdk.httpclient.keepalive.timeout=20` |
| **HttpURLConnection / `SimpleClientHttpRequestFactory`** | `-Dhttp.keepAlive=false` (só para teste) ou `-Dhttp.keepAliveTimeout` |

**Exemplo: Apache HttpClient 5 com Spring `RestClient`:**

```java
var connConfig = ConnectionConfig.custom()
        .setTimeToLive(TimeValue.ofSeconds(30))     // renova a conexão
        .build();

var cm = PoolingHttpClientConnectionManagerBuilder.create()
        .setDefaultConnectionConfig(connConfig)
        .build();

var httpClient = HttpClients.custom().setConnectionManager(cm).build();

RestClient client = RestClient.builder()
        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
        .build();
```

**Exemplo: Reactor Netty (WebClient):**

```java
ConnectionProvider provider = ConnectionProvider.builder("pool")
        .maxLifeTime(Duration.ofSeconds(30))
        .maxIdleTime(Duration.ofSeconds(20))
        .build();

WebClient client = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(HttpClient.create(provider)))
        .build();
```

> O Reactor Netty pode usar o resolvedor DNS do próprio Netty (que respeita o TTL do registro DNS e não a propriedade da JVM). Teste o comportamento no seu ambiente.

**Boas práticas no cliente:**

- Defina **timeouts** de conexão e de resposta.
- **Retry** só para chamadas idempotentes (GET, PUT, DELETE) e com limite.
- Nunca use `networkaddress.cache.ttl=-1` em ambiente com instâncias elásticas.

---

## 4. gRPC

### 4.1 Por que o gRPC é diferente

| Característica | Efeito |
|---|---|
| Resolvedor DNS **próprio** | Lê `networkaddress.cache.ttl` como **System property** (padrão 30s) |
| **Uma conexão HTTP/2** multiplexada por IP | Não existe "pool" que renove sozinho |
| Política padrão **`pick_first`** | Conecta em **um** IP e fica nele |
| Re-resolução **não é periódica** | O DNS só é consultado de novo quando a conexão cai ou recebe `GOAWAY` |

Ou seja: com a configuração padrão do gRPC, **todo o tráfego vai para uma única réplica** e ele não percebe réplicas novas.

### 4.2 As três peças obrigatórias

```
  round_robin           →  usa TODOS os IPs resolvidos
+ TTL de DNS curto      →  a re-resolução enxerga a lista nova
+ maxConnectionAge      →  o servidor força a re-resolução (GOAWAY)
────────────────────────────────────────────────────────────────
  = scale up e scale down detectados em poucos segundos
```

### 4.3 Configuração no SERVER (gRPC)

```java
Server server = NettyServerBuilder.forPort(9090)
        .addService(new MeuServico())

        // Renova conexões: envia GOAWAY após ~este tempo (o gRPC aplica jitter de ±10%)
        .maxConnectionAge(60, TimeUnit.SECONDS)
        // Tempo para RPCs em andamento terminarem depois do GOAWAY
        .maxConnectionAgeGrace(10, TimeUnit.SECONDS)

        // Encerra conexões ociosas
        .maxConnectionIdle(5, TimeUnit.MINUTES)

        // Política de keepalive (evita clientes agressivos derrubarem a conexão)
        .permitKeepAliveTime(30, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(false)
        .build()
        .start();
```

**Desligamento gracioso (essencial no scale down):**

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    server.shutdown();                                  // para de aceitar novas RPCs, envia GOAWAY
    try {
        if (!server.awaitTermination(20, TimeUnit.SECONDS)) {
            server.shutdownNow();
        }
    } catch (InterruptedException e) {
        server.shutdownNow();
    }
}));
```

| Parâmetro | Valor típico | Trade-off |
|---|---|---|
| `maxConnectionAge` | 30s a 5min | Menor = convergência mais rápida, porém mais reconexões (custo de handshake) |
| `maxConnectionAgeGrace` | 5 a 30s | Deve ser maior que a RPC mais longa esperada |
| `maxConnectionIdle` | 1 a 10min | Libera recursos de clientes inativos |

### 4.4 Configuração no CLIENT (gRPC)

**Passo 1: TTL do DNS como System property** (flag da JVM ou primeira linha do `main`):

```java
System.setProperty("networkaddress.cache.ttl", "5");
Security.setProperty("networkaddress.cache.ttl", "5");   // para o InetAddress do JDK
```

**Passo 2: canal com `dns:///` e `round_robin`:**

```java
ManagedChannel channel = ManagedChannelBuilder
        .forTarget("dns:///backend:9090")        // "dns:///" garante o resolvedor DNS
        .defaultLoadBalancingPolicy("round_robin")
        .idleTimeout(5, TimeUnit.MINUTES)
        .keepAliveTime(60, TimeUnit.SECONDS)     // detecta conexões "mortas"
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .defaultServiceConfig(serviceConfigComRetry())   // opcional, veja abaixo
        .enableRetry()
        .usePlaintext()                          // use TLS em produção
        .build();
```

**Passo 3 (opcional): retry para `UNAVAILABLE`** (cobre o instante em que uma réplica some):

```java
private static Map<String, Object> serviceConfigComRetry() {
    return Map.of("methodConfig", List.of(Map.of(
        "name", List.of(Map.of()),                       // vale para todos os métodos
        "retryPolicy", Map.of(
            "maxAttempts", 3.0,
            "initialBackoff", "0.1s",
            "maxBackoff", "1s",
            "backoffMultiplier", 2.0,
            "retryableStatusCodes", List.of("UNAVAILABLE")))));
}
```

> Só habilite retry para métodos **idempotentes**.

**Plano B quando você não controla o servidor:** chame `channel.enterIdle()` periodicamente (ex.: a cada 30s com um `ScheduledExecutorService`). O canal volta a conectar e re-resolver o DNS na próxima chamada.

### 4.5 O que cada cenário produz

| Configuração | Distribui entre réplicas | Detecta scale up | Detecta scale down |
|---|---|---|---|
| `pick_first` (padrão) | ❌ Uma só | ❌ | ⚠️ Só se cair a réplica usada |
| `round_robin`, sem `maxConnectionAge` | ✅ | ❌ **Armadilha** | ✅ Após falha de conexão |
| `round_robin` + `maxConnectionAge` + TTL curto | ✅ | ✅ (~`maxConnectionAge`) | ✅ |
| TTL `-1` (eterno) | ✅ | ❌ | ❌ Erros persistentes |

---

## 5. Comparativo HTTP × gRPC

| Aspecto | HTTP/1.1 (REST) | gRPC |
|---|---|---|
| Cache DNS lido de | **Security** property | **System** property |
| Conexões | Várias, em pool | **Uma** por IP, longa duração |
| Balanceamento padrão | Por conexão, via ordem do DNS | `pick_first` (nenhum) |
| Renovação pelo servidor | `keep-alive-timeout`, `max-keep-alive-requests`, `Connection: close` | `maxConnectionAge` (GOAWAY) |
| Renovação pelo cliente | `timeToLive` / `maxLifeTime` do pool | `enterIdle()` / `idleTimeout` |
| Mudança para o cliente enxergar | TTL + vida da conexão | TTL + `round_robin` + `maxConnectionAge` |

---

## 6. Resumo por papel

### 🖥️ Quem opera o SERVIDOR (vale para todos os clientes)

- [ ] Renovar conexões periodicamente (`maxConnectionAge` no gRPC; `keep-alive-timeout` / `max-keep-alive-requests` no HTTP).
- [ ] Implementar **desligamento gracioso** (`server.shutdown=graceful` / `server.shutdown()` + `awaitTermination`).
- [ ] Garantir que a instância **saia do DNS antes de parar** (readiness / healthcheck).
- [ ] Em Kubernetes: Service **headless**, `preStop` com pequena espera e `terminationGracePeriodSeconds` adequado.

### 📱 Quem desenvolve o CLIENTE

- [ ] `networkaddress.cache.ttl` curto (ex.: **5s**) no lugar certo: **Security** para HTTP e **System** para gRPC. Defina antes da primeira resolução.
- [ ] Nunca usar TTL `-1`.
- [ ] HTTP: limitar a vida da conexão no pool (`timeToLive` / `maxLifeTime`).
- [ ] gRPC: `dns:///` + `round_robin` + keepalive. Opcional: retry de `UNAVAILABLE` e `enterIdle()` periódico.
- [ ] Timeouts e retries apenas em operações idempotentes.

---

## 7. Valores de partida sugeridos

| Parâmetro | Valor inicial | Observação |
|---|---|---|
| `networkaddress.cache.ttl` (cliente) | **5s** | Pode subir para 10 a 30s se o DNS sofrer carga |
| `networkaddress.cache.negative.ttl` | 0 a 5s | Evita "lembrar" falhas |
| gRPC `maxConnectionAge` (servidor) | **30 a 60s** | Convergência ≈ esse valor |
| gRPC `maxConnectionAgeGrace` | 10s | Maior que a RPC mais longa |
| HTTP pool `timeToLive` / `maxLifeTime` (cliente) | **30s** | Ajuste conforme tolerância |
| Tomcat `keep-alive-timeout` | 20s | |
| Desligamento gracioso | 20 a 30s | Maior que a requisição mais longa |

---

## 8. Armadilhas comuns

1. **TTL configurado tarde demais.** A classe já leu o valor padrão. Coloque na primeira linha do `main` ou use flags da JVM.
2. **Propriedade no lugar errado.** Security para `InetAddress`, System para o gRPC.
3. **TTL curto, mas conexão eterna.** Sem renovar a conexão, o DNS nunca é consultado de novo.
4. **`pick_first` achando que balanceia.** Todo o tráfego cai em uma réplica.
5. **Scale up "não funciona" no gRPC.** Sem `maxConnectionAge`, nenhuma conexão cai e ninguém re-resolve.
6. **Proxy/Service com IP virtual no meio.** O cliente só vê o IP do proxy e a mudança acontece atrás dele.
7. **Desligar a réplica sem drenar.** Gera `UNAVAILABLE`/`Connection reset` para requisições em andamento.
8. **Retry em operação não idempotente.** Pode duplicar efeitos (pagamento, criação).

---

## 9. Como validar (ambiente local)

| O que testar | Como |
|---|---|
| IPs que o DNS entrega | `podman exec <app> getent ahosts backend` |
| IPs que a JVM enxerga | Endpoint de teste com `InetAddress.getAllByName("backend")` |
| Distribuição entre réplicas | Chamar o endpoint em loop e contar por `hostname` |
| Scale down | `podman compose up -d --scale backend=1` |
| Scale up | `podman compose up -d --scale backend=5` |
| Logs de resolução do gRPC | Nível `FINE` em `io.grpc.internal.DnsNameResolver` |

**Resultado esperado na configuração recomendada:** em até `max(maxConnectionAge, TTL)` segundos após o scale, a distribuição reflete o novo número de réplicas e, no scale down, os erros desaparecem rapidamente.

---

## 10. Quando considerar outra abordagem

| Situação | Alternativa |
|---|---|
| Muitos clientes, polítícas complexas de LB | **Service mesh** (Istio, Linkerd) ou proxy (Envoy) |
| Descoberta dinâmica sem depender de DNS | **xDS** (gRPC suporta `xds:///`), Consul, Eureka |
| Controle fino de saúde por instância | Health checking no cliente gRPC (`grpc.health.v1`) |
| Não se pode alterar o servidor | Renovação no cliente (`enterIdle()`, `maxLifeTime`) |

---

### Ideia para guardar

> **DNS curto + conexão que expira + (gRPC) round_robin = o cliente acompanha a infraestrutura.**
> Se faltar uma dessas peças, o cliente fica preso ao passado.
