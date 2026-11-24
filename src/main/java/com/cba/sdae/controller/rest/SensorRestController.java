package com.cba.sdae.controller.rest;

import com.cba.sdae.model.EstadoEsteira;
import com.cba.sdae.model.LeituraHistorico;
import com.cba.sdae.model.Sensor;
import com.cba.sdae.model.TipoEventoEsteira;
import com.cba.sdae.repository.LeituraHistoricoRepository;
import com.cba.sdae.service.EsteiraService;
import com.cba.sdae.service.EventoEsteiraService;
import com.cba.sdae.service.SensorService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sensores")
public class SensorRestController {

    private final SensorService              sensorService;
    private final EsteiraService             esteiraService;
    private final LeituraHistoricoRepository leituraRepo;
    private final EventoEsteiraService       eventoService;

    public SensorRestController(SensorService sensorService,
                                EsteiraService esteiraService,
                                LeituraHistoricoRepository leituraRepo,
                                EventoEsteiraService eventoService) {
        this.sensorService  = sensorService;
        this.esteiraService = esteiraService;
        this.leituraRepo    = leituraRepo;
        this.eventoService  = eventoService;
    }

    @GetMapping
    public List<Sensor> listar() { return sensorService.listarTodos(); }

    @GetMapping("/{id}")
    public ResponseEntity<Sensor> buscarPorId(@PathVariable Long id) {
        return sensorService.buscarPorId(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/leitura")
    public ResponseEntity<Double> leitura(@PathVariable Long id) {
        return sensorService.buscarPorId(id)
                .map(s -> ResponseEntity.ok(s.getLeituraAtual()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/historico")
    public ResponseEntity<List<LeituraHistorico>> historico(@PathVariable Long id,
                                                            @RequestParam(defaultValue = "30") int qtd) {
        if (sensorService.buscarPorId(id).isEmpty()) return ResponseEntity.notFound().build();
        int limit = Math.clamp(qtd, 1, 100);
        return ResponseEntity.ok(leituraRepo.findBySensor_IdOrderByDataHoraDesc(id, PageRequest.of(0, limit)));
    }

    @GetMapping("/leituras")
    public ResponseEntity<List<LeituraHistorico>> listarLeituras(
            @RequestParam(required = false) Long esteiraId,
            @RequestParam(required = false) Long sensorId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamanho) {
        int limit = Math.clamp(tamanho, 1, 200);
        int page = Math.max(0, pagina);
        return ResponseEntity.ok(leituraRepo.filtrarLeituras(esteiraId, sensorId, PageRequest.of(page, limit)));
    }

    @GetMapping("/esteira/{esteiraId}")
    public ResponseEntity<List<Sensor>> porEsteira(@PathVariable Long esteiraId) {
        if (esteiraService.buscarPorId(esteiraId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(sensorService.listarPorEsteira(esteiraId));
    }

    @PostMapping
    public ResponseEntity<?> criar(@Valid @RequestBody Sensor sensor,
                                   @RequestParam(required = false) Long esteiraId) {
        sensor.setId(null);
        sensor.setApiKey(null);
        sensor.setExcluidoEm(null);
        sensor.setExcluidoPor(null);
        Long targetEsteiraId = esteiraId != null ? esteiraId : sensor.getEsteiraIdTransient();
        if (targetEsteiraId != null) {
            var esteira = esteiraService.buscarPorId(targetEsteiraId);
            if (esteira.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("erro", "Esteira não encontrada: " + targetEsteiraId));
            sensor.setEsteira(esteira.get());
        }
        if (sensor.getEsteira() == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Informe a esteira vinculada ao sensor."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(sensorService.salvar(sensor));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> atualizar(@PathVariable Long id,
                                       @Valid @RequestBody Sensor dados,
                                       @RequestParam(required = false) Long esteiraId) {
        Long targetEsteiraId = esteiraId != null ? esteiraId : dados.getEsteiraIdTransient();
        return sensorService.buscarPorId(id).map(s -> {
            if (dados.getModelo()       != null) s.setModelo(dados.getModelo());
            if (dados.getLeituraAtual() != null) s.setLeituraAtual(dados.getLeituraAtual());
            if (dados.getDescricao()    != null) s.setDescricao(dados.getDescricao());
            if (dados.getTipo()         != null) s.setTipo(dados.getTipo());
            if (dados.getStatus()       != null) s.setStatus(dados.getStatus());
            if (targetEsteiraId != null) {
                var esteira = esteiraService.buscarPorId(targetEsteiraId);
                if (esteira.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("erro", "Esteira não encontrada: " + targetEsteiraId));
                }
                s.setEsteira(esteira.get());
            }
            return ResponseEntity.ok(sensorService.salvar(s));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/rotacionar-api-key")
    public ResponseEntity<?> rotacionarApiKey(@PathVariable Long id) {
        try {
            Sensor sensor = sensorService.rotacionarApiKey(id);
            return ResponseEntity.ok(Map.of(
                "mensagem", "Chave de API rotacionada com sucesso",
                "apiKey", sensor.getApiKey()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Endpoint oficial usado pelo ESP32 com autenticação via X-API-Key.
     * Suporta novo payload (valorLdr, laserDetectado, pulsoDetectado) e formato legado (valor).
     */
    @PostMapping("/{id}/leitura")
    public ResponseEntity<?> registrarLeitura(@PathVariable Long id,
                                              @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                              @RequestBody(required = false) Map<String, Object> body) {
        var optSensor = sensorService.buscarPorId(id);
        if (optSensor.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Sensor s = optSensor.get();

        // Validação da API Key
        if (s.getApiKey() == null || apiKey == null ||
            !java.security.MessageDigest.isEqual(
                s.getApiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("erro", "Chave de API inválida ou ausente para este sensor."));
        }

        return processarPayloadLeitura(s, body);
    }

    private ResponseEntity<?> processarPayloadLeitura(Sensor s, Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Payload de leitura não fornecido."));
        }

        Integer valorLdr = parseInteger(body.get("valorLdr"));
        Boolean laserDetectado = parseBoolean(body.get("laserDetectado"));
        Boolean pulsoDetectado = parseBoolean(body.containsKey("pulsoDetectado")
                ? body.get("pulsoDetectado") : body.get("movimentoDetectado"));
        Double valorLegado = parseDouble(body.get("valor"));
        LocalDateTime timestamp = null;

        if (body.containsKey("timestamp") && body.get("timestamp") != null) {
            try {
                timestamp = LocalDateTime.parse(body.get("timestamp").toString());
            } catch (Exception ignored) {}
        }

        // Campos nativos do módulo MPU-6050 (acelerômetro 3 eixos em g + giroscópio 3 eixos em °/s)
        Double ax = firstDouble(body, "ax", "aceleracaoX");
        Double ay = firstDouble(body, "ay", "aceleracaoY");
        Double az = firstDouble(body, "az", "aceleracaoZ");
        Double gx = firstDouble(body, "gx", "giroscopioX");
        Double gy = firstDouble(body, "gy", "giroscopioY");
        Double gz = firstDouble(body, "gz", "giroscopioZ");
        Double vibracaoRmsG  = firstDouble(body, "vibracaoRmsG", "vibracaoRms");
        Double vibracaoPicoG = firstDouble(body, "vibracaoPicoG", "vibracaoPico");
        Double gyroPicoDps   = firstDouble(body, "gyroPicoDps", "velocidadeAngular");
        Boolean sensorOk     = parseBoolean(body.containsKey("sensorOk") ? body.get("sensorOk") : body.get("mpuConectado"));

        com.cba.sdae.model.Mpu6050Telemetria mpu = new com.cba.sdae.model.Mpu6050Telemetria(
                ax, ay, az, gx, gy, gz, vibracaoRmsG, vibracaoPicoG, gyroPicoDps, sensorOk);

        // Validação mínima: precisa ter ao menos telemetria MPU-6050, valorLdr, pulsoDetectado ou valor
        if (valorLdr == null && valorLegado == null && pulsoDetectado == null && !mpu.temDados()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", "Informe telemetria MPU-6050 (ax/ay/az, gx/gy/gz, vibracaoRmsG, gyroPicoDps), 'valorLdr', 'pulsoDetectado' ou 'valor'."));
        }

        Map<String, Object> resultado = sensorService.processarTelemetria(
                s.getId(), valorLdr, laserDetectado, pulsoDetectado, timestamp, valorLegado, mpu.temDados() ? mpu : null);

        return ResponseEntity.ok(resultado);
    }

    private Integer parseInteger(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        try { return Integer.parseInt(raw.toString().trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private Double parseDouble(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(raw.toString().trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private Boolean parseBoolean(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        return Boolean.parseBoolean(raw.toString().trim());
    }

    private Double firstDouble(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key) && body.get(key) != null) {
                Double v = parseDouble(body.get(key));
                if (v != null) return v;
            }
        }
        return null;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        if (sensorService.buscarPorId(id).isEmpty()) return ResponseEntity.notFound().build();
        sensorService.deletar(id);
        return ResponseEntity.noContent().build();
    }
}