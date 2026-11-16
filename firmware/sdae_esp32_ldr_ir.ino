/*
 * SDAE - ESP32 com sensor LDR + módulo IR LM393 (IR substitui o laser)
 *
 * Ligação:
 *   LDR:  3V3 -> LDR -> ponto central -> resistor 10k -> GND
 *         ponto central -> GPIO 34 (ADC1, funciona com WiFi ligado)
 *   IR LM393:  VCC -> 3V3 | GND -> GND | OUT -> GPIO 27
 *
 * Envia a cada 2s para: POST /api/sensores/{id}/leitura
 * Cabeçalho: X-API-Key
 * Corpo JSON: {"valorLdr": 0-4095, "pulsoDetectado": true|false}
 *
 * O backend decide se o "feixe" está presente comparando valorLdr com o
 * ldrThreshold da esteira (padrão 1800), como já fazia com o laser.
 */

#include <WiFi.h>
#include <HTTPClient.h>

// ======== CONFIGURE AQUI ========
const char* WIFI_SSID  = "SEU_WIFI";
const char* WIFI_SENHA = "SUA_SENHA";

// IP do PC onde o Spring Boot roda (rode "ipconfig" no Windows e use o IPv4).
// O "1" no final do caminho é o ID do sensor cadastrado no SDAE.
const char* URL     = "http://192.168.0.10:8080/api/sensores/1/leitura";
const char* API_KEY = "COLE_A_API_KEY_DO_SENSOR";
// =================================

const int PINO_LDR = 34;
const int PINO_IR  = 27;
const unsigned long INTERVALO_ENVIO_MS = 2000;
const unsigned long DEBOUNCE_MS        = 50;

volatile unsigned long pulsos = 0;
volatile unsigned long ultimoPulso = 0;

// O módulo LM393 põe a saída em LOW quando detecta algo, por isso FALLING.
void IRAM_ATTR contaPulso() {
  unsigned long agora = millis();
  if (agora - ultimoPulso > DEBOUNCE_MS) {
    pulsos++;
    ultimoPulso = agora;
  }
}

// Média de 10 leituras para reduzir ruído do LDR
int leLdr() {
  long soma = 0;
  for (int i = 0; i < 10; i++) {
    soma += analogRead(PINO_LDR);
    delay(2);
  }
  return soma / 10;
}

void conectaWifi() {
  Serial.print("Conectando ao WiFi");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_SENHA);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.print("\nWiFi conectado! IP do ESP32: ");
  Serial.println(WiFi.localIP());
}

void enviaLeitura(int valorLdr, bool pulsoDetectado) {
  HTTPClient http;
  http.begin(URL);
  http.setTimeout(5000);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-API-Key", API_KEY);

  String json = String("{\"valorLdr\":") + valorLdr +
                ",\"pulsoDetectado\":" + (pulsoDetectado ? "true" : "false") + "}";

  int codigo = http.POST(json);
  Serial.printf("POST %s -> HTTP %d\n", json.c_str(), codigo);

  if (codigo > 0) {
    Serial.println(http.getString());
  } else {
    Serial.printf("Falha na conexão: %s\n", http.errorToString(codigo).c_str());
  }
  http.end();
}

void setup() {
  Serial.begin(115200);
  analogReadResolution(12);          // LDR: 0 a 4095
  pinMode(PINO_IR, INPUT);
  attachInterrupt(digitalPinToInterrupt(PINO_IR), contaPulso, FALLING);
  conectaWifi();
}

void loop() {
  static unsigned long ultimoEnvio = 0;

  if (WiFi.status() != WL_CONNECTED) {
    conectaWifi();
  }

  if (millis() - ultimoEnvio >= INTERVALO_ENVIO_MS) {
    ultimoEnvio = millis();

    noInterrupts();
    unsigned long p = pulsos;
    pulsos = 0;
    interrupts();

    int ldr = leLdr();
    Serial.printf("LDR: %d | Pulsos IR nos últimos 2s: %lu\n", ldr, p);
    enviaLeitura(ldr, p > 0);
  }
}
