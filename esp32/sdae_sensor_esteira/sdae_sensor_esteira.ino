/* =========================================================================
   SDAE — Firmware ESP32 (Sensor Laser + LDR para Detecção de Movimento)
   =========================================================================
   Objetivo do Hardware:
     LASER (KY-008) → Feixe contínuo apontado para o LDR (KY-018)
     O movimento da esteira provoca variações periódicas ou interrupções no feixe.
     O ESP32 analisa as transições, calcula pulsos válidos com debounce e
     determina o estado real de funcionamento.

   Estados Identificados:
     - ESTEIRA FUNCIONANDO (RUNNING): Pulsos periódicos válidos detectados.
     - ESTEIRA PARADA (STOPPED): Sem pulsos durante o STOP_TIMEOUT configurado.
     - SENSOR OFFLINE (detectado pelo servidor se o ESP32 parar de enviar telemetria/heartbeat).

   Ligação Física (ESP32):
     Laser KY-008:
       VCC -> 3V3 do ESP32
       GND -> GND
       (Feixe laser contínuo alinhado com o LDR)
     
     Sensor LDR (KY-018 ou divisor com resistor 10k):
       VCC -> 3V3
       GND -> GND
       AO  -> GPIO 34 (ADC1 canal 6, pino de entrada analógica de alta precisão)

   Procedimento de Calibração:
     Abra o Monitor Serial (115200 baud).
     O firmware exibirá em tempo real:
       LDR atual: 1840 | LDR mínimo: 1750 | LDR máximo: 1920 | Laser: DETECTADO | Pulso: SIM
     1) Feixe do laser estável e desobstruído sobre o LDR: anote o valor máximo (ex: ~2800).
     2) Interrompa o feixe com o obstáculo/esteira: anote o valor mínimo (ex: ~800).
     3) Defina LDR_THRESHOLD no valor intermediário (ex: (2800 + 800) / 2 = 1800).
   ========================================================================= */

#include <WiFi.h>
#include <HTTPClient.h>

// ── 1. CONFIGURAÇÃO DA REDE WI-FI ───────────────────────────────────────
const char* WIFI_SSID     = "NOME_DA_SUA_REDE";
const char* WIFI_PASSWORD = "SENHA_DA_SUA_REDE";

// ── 2. CONFIGURAÇÃO DO SERVIDOR (Spring Boot) ───────────────────────────
// Use o IP local da máquina onde o Spring Boot está rodando (ex: 192.168.0.105)
const char* SERVER_HOST = "192.168.0.105";
const int   SERVER_PORT = 8080;

// ── 3. IDENTIFICAÇÃO DO SENSOR ──────────────────────────────────────────
// SENSOR_ID: ID gerado no cadastro do sensor no sistema web
// API_KEY: Copiada da tela de edição do sensor no sistema web
const long  SENSOR_ID = 1;
const char* API_KEY   = "COLE_AQUI_A_CHAVE_DO_SENSOR";

// ── 4. PINO E LIMIARES DO SENSOR ────────────────────────────────────────
const int PINO_LDR = 34;

// Limiar analógico do ADC (0 a 4095). Leituras acima são consideradas feixe presente.
const int LDR_THRESHOLD = 1800;

// Histerese para evitar oscilações na borda do limiar (debounce por nível)
const int HISTERESE = 100;

// ── 5. TEMPOS E AMOSTRAGEM (milissegundos) ──────────────────────────────
const unsigned long INTERVALO_AMOSTRAGEM = 25;    // Amostra LDR a cada 25ms (40Hz)
const unsigned long INTERVALO_ENVIO      = 2500;  // Telemetria/Heartbeat a cada 2.5 segundos
const unsigned long STOP_TIMEOUT         = 10000; // 10s sem pulsos = Esteira Parada
const unsigned long DEBOUNCE_PULSO_MS    = 150;   // Intervalo mínimo entre pulsos físicos

// ── VARIÁVEIS DE ESTADO INTERNO ─────────────────────────────────────────
unsigned long ultimoTempoAmostra = 0;
unsigned long ultimoTempoEnvio   = 0;
unsigned long tempoUltimoPulso   = 0;
unsigned long tempoUltimaTransicao = 0;

int ldrMinimoJanela = 4095;
int ldrMaximoJanela = 0;
int ultimaLeituraLdr = 0;

bool estadoLaserAnterior = false;
bool pulsoDetectadoNestaJanela = false;
unsigned long pulsosAcumulados = 0;
unsigned int pulsosNaJanela = 0;

// ── FUNÇÃO DE CONEXÃO WI-FI COM RECONEXÃO AUTOMÁTICA ───────────────────
void conectarWifi() {
    if (WiFi.status() == WL_CONNECTED) return;

    Serial.print("[WiFi] Conectando a ");
    Serial.print(WIFI_SSID);

    WiFi.disconnect();
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    int tentativas = 0;
    while (WiFi.status() != WL_CONNECTED && tentativas < 20) {
        delay(300);
        Serial.print(".");
        tentativas++;
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.print("[WiFi] Conectado com sucesso! IP do ESP32: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println("[WiFi] Falha ao conectar. Operando offline temporariamente...");
    }
}

// ── AMOSTRAGEM DO LDR E DETECÇÃO DE PULSOS ─────────────────────────────
void amostrarLdrEPulsos() {
    int leitura = analogRead(PINO_LDR);
    ultimaLeituraLdr = leitura;

    // Atualiza mínimo e máximo para calibração
    if (leitura < ldrMinimoJanela) ldrMinimoJanela = leitura;
    if (leitura > ldrMaximoJanela) ldrMaximoJanela = leitura;

    // Detecção com histerese
    bool laserDetectado;
    if (leitura > (LDR_THRESHOLD + HISTERESE)) {
        laserDetectado = true;
    } else if (leitura < (LDR_THRESHOLD - HISTERESE)) {
        laserDetectado = false;
    } else {
        laserDetectado = estadoLaserAnterior;
    }

    unsigned long agora = millis();

    // Transição de feixe (interrupção do laser ou retorno com debounce)
    if (laserDetectado != estadoLaserAnterior) {
        if ((agora - tempoUltimaTransicao) >= DEBOUNCE_PULSO_MS) {
            // Conta como transição/pulso físico da esteira
            pulsoDetectadoNestaJanela = true;
            pulsosNaJanela++;
            pulsosAcumulados++;
            tempoUltimoPulso = agora;
            tempoUltimaTransicao = agora;
        }
        estadoLaserAnterior = laserDetectado;
    }
}

// ── ENVIO DE TELEMETRIA E HEARTBEAT VIA HTTP POST ──────────────────────
void enviarTelemetria() {
    unsigned long agora = millis();
    unsigned long tempoSemPulsos = agora - tempoUltimoPulso;
    bool esteiraParada = (tempoUltimoPulso > 0 && tempoSemPulsos >= STOP_TIMEOUT);

    bool laserPresente = (ultimaLeituraLdr >= LDR_THRESHOLD);

    // Relatório do Monitor Serial para calibração e acompanhamento
    Serial.print("LDR atual: ");
    Serial.print(ultimaLeituraLdr);
    Serial.print(" | LDR mínimo: ");
    Serial.print(ldrMinimoJanela);
    Serial.print(" | LDR máximo: ");
    Serial.print(ldrMaximoJanela);
    Serial.print(" | Laser: ");
    Serial.print(laserPresente ? "DETECTADO" : "INTERROMPIDO");
    Serial.print(" | Pulso: ");
    Serial.print(pulsoDetectadoNestaJanela ? "SIM" : "NAO");
    Serial.print(" | Pulsos na janela: ");
    Serial.print(pulsosNaJanela);
    Serial.print(" | Status Estimado: ");
    Serial.println(esteiraParada ? "ESTEIRA PARADA" : "ESTEIRA FUNCIONANDO");

    // Verifica conexão antes do envio
    if (WiFi.status() != WL_CONNECTED) {
        conectarWifi();
        if (WiFi.status() != WL_CONNECTED) {
            Serial.println("[HTTP] Não enviado: sem conexão Wi-Fi.");
            // Reseta janela mesmo sem envio
            pulsoDetectadoNestaJanela = false;
            pulsosNaJanela = 0;
            ldrMinimoJanela = ultimaLeituraLdr;
            ldrMaximoJanela = ultimaLeituraLdr;
            return;
        }
    }

    HTTPClient http;
    String url = String("http://") + SERVER_HOST + ":" + SERVER_PORT
                 + "/api/sensores/" + String(SENSOR_ID) + "/leitura";

    http.begin(url);
    http.setTimeout(2000); // 2 segundos de timeout para evitar travamento
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-API-Key", API_KEY);

    // Payload JSON completo conforme especificação da API SDAE
    String corpo = "{";
    corpo += "\"valorLdr\":" + String(ultimaLeituraLdr) + ",";
    corpo += "\"laserDetectado\":" + String(laserPresente ? "true" : "false") + ",";
    corpo += "\"pulsoDetectado\":" + String(pulsoDetectadoNestaJanela ? "true" : "false");
    corpo += "}";

    int codigoResposta = http.POST(corpo);

    if (codigoResposta > 0) {
        Serial.print("[HTTP] Enviado! Código HTTP: ");
        Serial.print(codigoResposta);
        String resposta = http.getString();
        Serial.print(" -> Resposta: ");
        Serial.println(resposta);
    } else {
        Serial.print("[HTTP] Falha na comunicação: ");
        Serial.println(http.errorToString(codigoResposta).c_str());
    }

    http.end();

    // Reseta acumuladores da janela de amostragem
    pulsoDetectadoNestaJanela = false;
    pulsosNaJanela = 0;
    ldrMinimoJanela = ultimaLeituraLdr;
    ldrMaximoJanela = ultimaLeituraLdr;
}

// ── SETUP INICIAL ───────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("\n==============================================");
    Serial.println("  SDAE — Monitoramento de Esteira (ESP32)");
    Serial.println("  Sensor Óptico: Laser KY-008 + LDR KY-018");
    Serial.println("==============================================");
    Serial.print("Pino LDR: GPIO "); Serial.println(PINO_LDR);
    Serial.print("Limiar LDR (Threshold): "); Serial.println(LDR_THRESHOLD);
    Serial.print("Sensor ID: "); Serial.println(SENSOR_ID);
    Serial.print("Servidor: http://"); Serial.print(SERVER_HOST); Serial.print(":"); Serial.println(SERVER_PORT);
    Serial.println("==============================================\n");

    analogReadResolution(12); // Resolução de 12 bits (0 a 4095)
    pinMode(PINO_LDR, INPUT);

    conectarWifi();

    tempoUltimoPulso = millis();
}

// ── LOOP PRINCIPAL NÃO-BLOQUEANTE ──────────────────────────────────────
void loop() {
    unsigned long agora = millis();

    // 1. Amostragem periódica de alta velocidade (LDR e pulsos)
    if (agora - ultimoTempoAmostra >= INTERVALO_AMOSTRAGEM) {
        ultimoTempoAmostra = agora;
        amostrarLdrEPulsos();
    }

    // 2. Envio de telemetria e heartbeat regular
    if (agora - ultimoTempoEnvio >= INTERVALO_ENVIO) {
        ultimoTempoEnvio = agora;
        enviarTelemetria();
    }

    // Yield para manter watchdog e tarefas de rede do ESP32 estáveis
    delay(1);
}
