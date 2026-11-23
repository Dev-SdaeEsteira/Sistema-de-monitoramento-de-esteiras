# Integração ESP32 — SDAE

Este documento explica como as peças se encaixam: o cadastro feito pelo
usuário no sistema web e o ESP32 físico que vai monitorar a esteira.

## Visão geral do fluxo

```
1. Usuário loga no sistema web (ENGENHEIRO ou ADMINISTRADOR)
        │
        ▼
2. Cadastra uma Esteira (nome, localização)          → /esteiras/nova
        │
        ▼
3. Cadastra um Sensor vinculado a essa esteira        → /sensores/novo
        │
        ▼
4. Sistema gera automaticamente uma "Chave de API"
   pra esse sensor (visível na tela de edição dele)   → /sensores/editar/{id}
        │
        ▼
5. Usuário copia o ID do sensor + a Chave de API
   e cola nas variáveis SENSOR_ID / API_KEY do
   firmware (arquivo .ino)
        │
        ▼
6. ESP32 liga, conecta no Wi-Fi, amostra o LDR a 40Hz (25ms)
   com histerese/debounce e a cada 2,5 segundos manda pro servidor:

       POST /api/sensores/{SENSOR_ID}/leitura
       Header: X-API-Key: <chave do sensor>
       Body:   {"valorLdr": 1840, "laserDetectado": true, "pulsoDetectado": true}
        │
        ▼
7. Backend (SensorRestController → SensorService):
     - Confere se a chave bate com a do sensor (comparação segura)
     - Salva a telemetria no sensor e avalia o estado da esteira (RUNNING/STOPPED/OFFLINE)
     - Calcula o nível de risco automaticamente:
         Laser detectado + pulso → NORMAL
         Feixe interrompido (com pulso) → ATENÇÃO
         Feixe interrompido (sem pulso) → CRÍTICO
     - Em transições de estado ou risco, gera Alerta e EventoEsteira
     - Grava um registro em LeituraHistorico (pro
       gráfico de histórico do sensor)
        │
        ▼
8. Dashboard, tela de Esteiras/Sensores e Relatórios
   já mostram os dados atualizados — nada disso
   precisa ser mexido, eles já leem do banco.
```

## O que já existia e o que foi criado agora

**Já existia (não precisou mudar):**
- Cadastro de Esteira e Sensor pelo usuário (telas + controllers).
- `SensorRestController` com o endpoint `POST /api/sensores/{id}/leitura`.
- `SensorService` calculando o nível de risco e gerando alertas
  automaticamente a cada leitura salva.
- `LeituraHistorico` guardando o histórico de cada leitura.

**Criado/ajustado agora para o ESP32 funcionar de verdade:**
- Campo `apiKey` na entidade `Sensor` — cada sensor tem sua própria chave,
  gerada automaticamente (UUID) no primeiro salvamento.
- Validação da chave (`X-API-Key`) dentro de `registrarLeitura()` —
  antes disso, o endpoint dependia de sessão/login, que um ESP32 não
  consegue fazer.
- `SecurityConfig`: a rota `POST /api/sensores/*/leitura` virou
  `permitAll()` na camada de sessão, porque agora a autenticação real
  acontece por chave dentro do controller (a rota já era isenta de CSRF).
- Tela de edição do sensor (`editarsensor.html`) agora mostra a chave de
  API com um botão de copiar.
- Ao cadastrar um sensor novo, o sistema já redireciona direto pra tela
  de edição dele (antes ia pra lista), pra você já sair copiando a chave.
- Firmware `esp32/sdae_sensor_esteira/sdae_sensor_esteira.ino` pronto
  pra gravar no ESP32.

## Testando sem o ESP32 ainda montado

Antes de mexer no hardware, dá pra testar se o backend está aceitando
leituras usando `curl` (ou Postman/Insomnia), simulando o que o ESP32
vai mandar:

```bash
curl -X POST http://localhost:8080/api/sensores/1/leitura \
  -H "Content-Type: application/json" \
  -H "X-API-Key: COLE_AQUI_A_CHAVE_DO_SENSOR_1" \
  -d "{\"valorLdr\": 1840, \"laserDetectado\": true, \"pulsoDetectado\": true}"
```

Se funcionar, a resposta é algo como:
```json
{"success": true, "sensorId": 1, "valorLdr": 1840, "laserDetectado": true, "pulsoDetectado": true, "estadoEsteira": "RUNNING", "nivelRisco": "NORMAL"}
```

E você deve ver o valor atualizado na tela de Sensores e, se o nível não
for NORMAL, um novo Alerta na tela de Relatórios.

## Preparando o firmware (Arduino IDE)

1. Instale o pacote de placas **ESP32** no Arduino IDE (Board Manager →
   busque "esp32" → instale o pacote da Espressif).
2. Abra `esp32/sdae_sensor_esteira/sdae_sensor_esteira.ino`.
3. Edite as constantes no topo do arquivo:
   - `WIFI_SSID` / `WIFI_PASSWORD` — sua rede Wi-Fi.
   - `SERVER_HOST` — o **IP local** do computador rodando o Spring Boot
     (não use `localhost`, o ESP32 é outro dispositivo na rede). Pra
     descobrir: `ipconfig` no Windows, procure "Endereço IPv4".
   - `SENSOR_ID` e `API_KEY` — pegue na tela de edição do sensor.
4. Selecione a placa certa em **Ferramentas → Placa** (ex: "ESP32 Dev
   Module") e a porta USB correta.
5. Grave o código (botão de upload) e abra o **Monitor Serial** (115200
   baud) pra acompanhar as leituras e calibrar (ver comentário no topo
   do `.ino` sobre `LDR_THRESHOLD` e `HISTERESE`).

## Ligação física (resumo — detalhes no `.ino`)

| Componente        | Pino ESP32 |
|--------------------|------------|
| Laser VCC          | 3V3        |
| Laser GND          | GND        |
| LDR (módulo) VCC   | 3V3        |
| LDR (módulo) GND   | GND        |
| LDR (módulo) AO    | GPIO 34    |

O laser fica sempre aceso, apontado diretamente para o LDR através da linha da esteira. O ESP32 amostra o LDR a 40 Hz (25 ms) com histerese (`LDR_THRESHOLD ± 100`) e debounce de 150 ms: quando a movimentação da esteira gera transições/pulsos no feixe, o sistema mantém o estado `RUNNING`; após 15 segundos sem pulsos (`STOP_TIMEOUT`), sinaliza `STOPPED`; e se o feixe ficar interrompido sem pulsos, o servidor registra `LASER_LOST` e dispara alerta crítico imediatamente.

## Coisas para ter em mente (próximos passos possíveis)

- **Rede:** o ESP32 e o servidor precisam estar na mesma rede Wi-Fi (ou
  você precisa expor a porta 8080 de outra forma). Se o servidor rodar
  num notebook que muda de IP toda hora, considere fixar um IP local
  pro PC no roteador (IP reservado por MAC).
- **Vários sensores:** cada ESP32/sensor físico tem sua própria
  `SENSOR_ID` + `API_KEY` — se você tiver mais de uma esteira/sensor,
  grava um `.ino` (com essas duas constantes diferentes) por dispositivo.
- **HTTPS:** hoje o firmware usa `http://` simples, adequado pra rede
  local/laboratório. Se um dia o servidor for exposto na internet,
  isso precisaria virar HTTPS.
