package com.cba.sdae.service;

import com.cba.sdae.model.*;
import com.cba.sdae.repository.AlertaRepository;
import com.cba.sdae.repository.EsteiraRepository;
import com.cba.sdae.repository.LeituraHistoricoRepository;
import com.cba.sdae.repository.SensorRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SensorService {

    private final SensorRepository           sensorRepository;
    private final EsteiraRepository          esteiraRepository;
    private final AlertaRepository           alertaRepository;
    private final LeituraHistoricoRepository leituraHistoricoRepository;
    private final AuditoriaService           auditoriaService;
    private final EventoEsteiraService       eventoService;

    // Cache em memória para debounce de alertas por sensor
    private final Map<Long, LocalDateTime> ultimoAlertaPorSensor = new ConcurrentHashMap<>();
    private final Map<Long, NivelRisco>    ultimoNivelPorSensor  = new ConcurrentHashMap<>();

    public SensorService(SensorRepository sensorRepository,
                         EsteiraRepository esteiraRepository,
                         AlertaRepository alertaRepository,
                         LeituraHistoricoRepository leituraHistoricoRepository,
                         AuditoriaService auditoriaService,
                         EventoEsteiraService eventoService) {
        this.sensorRepository           = sensorRepository;
        this.esteiraRepository          = esteiraRepository;
        this.alertaRepository           = alertaRepository;
        this.leituraHistoricoRepository = leituraHistoricoRepository;
        this.auditoriaService           = auditoriaService;
        this.eventoService              = eventoService;
    }

    public List<Sensor>     listarTodos()                      { return sensorRepository.findAllWithEsteira(); }
    public Optional<Sensor> buscarPorId(Long id)               { return sensorRepository.findById(id); }
    public List<Sensor>     listarPorEsteira(Long esteiraId)   { return sensorRepository.findByEsteira_Id(esteiraId); }

    @Transactional
    public Sensor rotacionarApiKey(Long id) {
        return sensorRepository.findById(id).map(sensor -> {
            String usuario = getUsuarioAtual();
            String apiKeyAntiga = sensor.getApiKey();
            
            // Gerar nova chave
            sensor.setApiKey(java.util.UUID.randomUUID().toString());
            Sensor salvo = sensorRepository.save(sensor);
            
            // Registrar auditoria
            String detalhes = String.format("Modelo: %s, Chave antiga: %s, Nova chave: %s", 
                sensor.getModelo(), 
                apiKeyAntiga != null && apiKeyAntiga.length() >= 8 ? apiKeyAntiga.substring(0, 8) + "..." : "N/A", 
                salvo.getApiKey().substring(0, 8) + "...");
            auditoriaService.registrar("ROTACIONAR_API_KEY", "SENSOR", id, usuario, detalhes);
            
            return salvo;
        }).orElseThrow(() -> new IllegalArgumentException("Sensor não encontrado: " + id));
    }

    public List<LeituraHistorico> listarHistorico(Long sensorId, int qtd) {
        int limit = Math.clamp(qtd, 1, 100);
        return leituraHistoricoRepository.findBySensor_IdOrderByDataHoraDesc(
                sensorId, org.springframework.data.domain.PageRequest.of(0, limit));
    }
    public List<Sensor>     listarPorNivelRisco(NivelRisco n)  { return sensorRepository.findByNivelRisco(n); }
    public long             contarTotal()                      { return sensorRepository.count(); }
    public long             contarPorNivelRisco(NivelRisco n)  { return sensorRepository.countByNivelRisco(n); }
    public double           buscarMaiorLeitura()               { Double max = sensorRepository.findMaxLeituraAtual(); return max != null ? max : 0.0; }
    
    @Transactional
    public void deletar(Long id) { 
        sensorRepository.findById(id).ifPresent(sensor -> {
            String usuario = getUsuarioAtual();
            sensor.setExcluidoEm(LocalDateTime.now());
            sensor.setExcluidoPor(usuario);
            sensorRepository.save(sensor);
            
            // Limpar cache de alertas
            ultimoAlertaPorSensor.remove(id);
            ultimoNivelPorSensor.remove(id);
            
            // Registrar auditoria
            String detalhes = String.format("Modelo: %s, Esteira: %s", 
                sensor.getModelo(), sensor.getEsteira() != null ? sensor.getEsteira().getNome() : "N/A");
            auditoriaService.registrar("EXCLUIR", "SENSOR", id, usuario, detalhes);
        });
    }

    @Transactional
    public Sensor salvar(Sensor sensor) {
        if (sensor == null || sensor.getModelo() == null || sensor.getModelo().isBlank()) {
            throw new IllegalArgumentException("O modelo do sensor é obrigatório.");
        }

        boolean isNova = sensor.getId() == null;
        Sensor entidadeParaSalvar = sensor;

        if (!isNova) {
            Optional<Sensor> optExistente = sensorRepository.findById(sensor.getId());
            if (optExistente.isPresent()) {
                Sensor existente = optExistente.get();
                // Se recebeu instância destacada (ex.: formulário MVC), preserva telemetria e metadados
                if (existente != sensor) {
                    existente.setModelo(sensor.getModelo());
                    if (sensor.getEsteira() != null) {
                        existente.setEsteira(sensor.getEsteira());
                    }
                    if (sensor.getLeituraAtual() != null) {
                        existente.setLeituraAtual(sensor.getLeituraAtual());
                    }
                    if (sensor.getDescricao() != null && !sensor.getDescricao().isBlank()) {
                        existente.setDescricao(sensor.getDescricao());
                    }
                    if (sensor.getTipo() != null && !sensor.getTipo().isBlank() && !"Laser + LDR".equals(sensor.getTipo())) {
                        existente.setTipo(sensor.getTipo());
                    }
                    if (sensor.getStatus() != null && !sensor.getStatus().isBlank() && !"ATIVO".equals(sensor.getStatus())) {
                        existente.setStatus(sensor.getStatus());
                    }
                }
                entidadeParaSalvar = existente;
            }
        }

        double leitura = entidadeParaSalvar.getLeituraAtual() != null ? entidadeParaSalvar.getLeituraAtual() : 0.0;
        NivelRisco nivel = calcularNivel(leitura);
        entidadeParaSalvar.setNivelRisco(nivel);

        if (entidadeParaSalvar.getApiKey() == null || entidadeParaSalvar.getApiKey().isBlank()) {
            entidadeParaSalvar.setApiKey(java.util.UUID.randomUUID().toString());
        }

        Sensor salvo = sensorRepository.save(entidadeParaSalvar);

        // Registrar auditoria
        String usuario = getUsuarioAtual();
        String acao = isNova ? "CRIAR" : "ATUALIZAR";
        String detalhes = String.format("Modelo: %s, Leitura: %.1f%%, Nível: %s",
            salvo.getModelo(), salvo.getLeituraAtual(), salvo.getNivelRisco());
        auditoriaService.registrar(acao, "SENSOR", salvo.getId(), usuario, detalhes);

        if (nivel != NivelRisco.NORMAL) {
            verificarEDispararAlerta(salvo, nivel, leitura, false);
        } else if (salvo.getId() != null) {
            ultimoNivelPorSensor.put(salvo.getId(), NivelRisco.NORMAL);
        }

        return salvo;
    }

    /**
     * Processa a telemetria enviada pelo ESP32 (ou simulador de teste)
     * e orquestra a lógica de detecção de movimento/parada, eventos e alertas.
     * Sobrecarga mantida para compatibilidade com sensores Laser + LDR e testes existentes.
     */
    @Transactional
    public Map<String, Object> processarTelemetria(Long sensorId, Integer valorLdr,
                                                   Boolean laserDetectado, Boolean pulsoDetectado,
                                                   LocalDateTime timestamp, Double valorLegado) {
        return processarTelemetria(sensorId, valorLdr, laserDetectado, pulsoDetectado, timestamp, valorLegado, null);
    }

    /**
     * Processa telemetria completa de sensores MPU-6050 (acelerômetro + giroscópio)
     * ou ópticos (Laser + LDR), persistindo os 6 eixos e vibração quando enviados.
     */
    @Transactional
    public Map<String, Object> processarTelemetria(Long sensorId, Integer valorLdr,
                                                   Boolean laserDetectado, Boolean pulsoDetectado,
                                                   LocalDateTime timestamp, Double valorLegado,
                                                   Mpu6050Telemetria mpu) {
        Sensor sensor = sensorRepository.findById(sensorId)
                .orElseThrow(() -> new IllegalArgumentException("Sensor não encontrado: " + sensorId));

        Esteira esteira = sensor.getEsteira();
        if (esteira == null) {
            throw new IllegalStateException("Sensor " + sensorId + " não está vinculado a nenhuma esteira.");
        }

        LocalDateTime agora = timestamp != null ? timestamp : LocalDateTime.now();
        boolean teveContatoAntes = sensor.getUltimoContato() != null;
        boolean laserAnterior = Boolean.TRUE.equals(sensor.getLaserDetectado());
        boolean temMpu = mpu != null && mpu.temDados();
        boolean modoMpu = temMpu || sensor.isMpu6050();

        // 1. Determina estado de saúde do sensor, movimento/pulso e índice de risco (%)
        boolean sensorOkMpu = true;
        if (temMpu && mpu.sensorOk() != null) {
            sensorOkMpu = mpu.sensorOk();
        } else if (modoMpu && laserDetectado != null) {
            sensorOkMpu = laserDetectado;
        }

        boolean pulso;
        if (pulsoDetectado != null) {
            pulso = pulsoDetectado;
        } else if (temMpu && sensorOkMpu) {
            double vibRms = mpu.vibracaoRmsG() != null ? mpu.vibracaoRmsG() : 0.0;
            double vibPico = mpu.vibracaoPicoG() != null ? mpu.vibracaoPicoG() : 0.0;
            double gyro = mpu.gyroPicoDps() != null ? mpu.gyroPicoDps() : 0.0;
            pulso = (vibRms >= 0.06 || vibPico >= 0.08 || gyro >= 8.0);
        } else {
            pulso = false;
        }

        double pct;
        int ldr;
        boolean laser;

        if (modoMpu) {
            if (!sensorOkMpu) {
                pct = valorLegado != null ? Math.clamp(valorLegado, 70.0, 100.0) : 95.0;
                pulso = false;
            } else if (valorLegado != null) {
                pct = Math.clamp(valorLegado, 0.0, 100.0);
            } else if (temMpu) {
                pct = calcularIndiceRiscoMpu(mpu);
            } else {
                pct = pulso ? 15.0 : 5.0;
            }
            ldr = valorLdr != null ? valorLdr : (int) Math.round(pct * 40.95);
            laser = sensorOkMpu;
        } else {
            // Fluxo original Laser + LDR preservado integralmente
            ldr = valorLdr != null ? valorLdr : (valorLegado != null ? (int) (valorLegado * 40.95) : (sensor.getUltimoValorLdr() != null ? sensor.getUltimoValorLdr() : 0));
            laser = laserDetectado != null ? laserDetectado : (ldr >= (esteira.getLdrThreshold() != null ? esteira.getLdrThreshold() : 1800));

            if (valorLegado != null) {
                pct = Math.clamp(valorLegado, 0.0, 100.0);
            } else if (!laser) {
                pct = pulso ? 50.0 : 85.0;
            } else {
                pct = pulso ? 10.0 : 25.0;
            }
        }

        NivelRisco nivelRiscoAnterior = sensor.getNivelRisco();
        NivelRisco nivelRisco = calcularNivel(pct);

        // 2. Atualiza dados do sensor
        sensor.setUltimoContato(agora);
        sensor.setUltimaLeitura(agora);
        sensor.setUltimoValorLdr(ldr);
        sensor.setLaserDetectado(laser);
        sensor.setLeituraAtual(pct);
        sensor.setNivelRisco(nivelRisco);

        if (temMpu) {
            if (mpu.ax() != null)            sensor.setAceleracaoX(mpu.ax());
            if (mpu.ay() != null)            sensor.setAceleracaoY(mpu.ay());
            if (mpu.az() != null)            sensor.setAceleracaoZ(mpu.az());
            if (mpu.gx() != null)            sensor.setGiroscopioX(mpu.gx());
            if (mpu.gy() != null)            sensor.setGiroscopioY(mpu.gy());
            if (mpu.gz() != null)            sensor.setGiroscopioZ(mpu.gz());
            if (mpu.vibracaoRmsG() != null)  sensor.setVibracaoRms(mpu.vibracaoRmsG());
            if (mpu.vibracaoPicoG() != null) sensor.setVibracaoPico(mpu.vibracaoPicoG());
            if (mpu.gyroPicoDps() != null)   sensor.setVelocidadeAngular(mpu.gyroPicoDps());
        }

        if (pulso) {
            sensor.setUltimoPulso(agora);
            sensor.setQuantidadePulsos(sensor.getQuantidadePulsos() + 1);
            esteira.setUltimoPulso(agora);
        }

        // 3. Atualiza dados da esteira e avalia transições de estado
        esteira.setUltimoContato(agora);
        EstadoEsteira estadoAnterior = esteira.getEstado();

        // Se esteira estiver em manutenção, preserva o estado MAINTENANCE
        if (estadoAnterior != EstadoEsteira.MAINTENANCE) {
            if (estadoAnterior == EstadoEsteira.OFFLINE) {
                // Comunicação restabelecida (ONLINE)
                eventoService.registrarTransicao(esteira, TipoEventoEsteira.ONLINE, EstadoEsteira.OFFLINE,
                        pulso ? EstadoEsteira.RUNNING : EstadoEsteira.STOPPED,
                        "Comunicação restabelecida com o sensor " + sensor.getModelo());

                if (pulso) {
                    esteira.setEstado(EstadoEsteira.RUNNING);
                    esteira.setTempoFuncionamentoInicio(agora);
                    esteira.setTempoParadaInicio(null);
                    eventoService.registrarTransicao(esteira, TipoEventoEsteira.START, EstadoEsteira.STOPPED,
                            EstadoEsteira.RUNNING, "Movimento detectado no retorno da comunicação");
                    alertaRepository.save(new Alerta("A esteira '" + esteira.getNome() + "' voltou a funcionar.",
                            agora, NivelRisco.NORMAL, esteira));
                } else {
                    esteira.setEstado(EstadoEsteira.STOPPED);
                    esteira.setTempoParadaInicio(agora);
                    alertaRepository.save(new Alerta("Comunicação restabelecida com a esteira '" + esteira.getNome() + "'. Esteira aguardando movimento.",
                            agora, NivelRisco.NORMAL, esteira));
                }
            } else if (pulso) {
                // Movimento / Pulso detectado
                if (estadoAnterior != EstadoEsteira.RUNNING) {
                    esteira.setEstado(EstadoEsteira.RUNNING);
                    esteira.setTempoFuncionamentoInicio(agora);
                    esteira.setTempoParadaInicio(null);

                    String descInicio = modoMpu
                            ? "Movimento detectado pelo sensor MPU-6050 " + sensor.getModelo()
                            : "Pulsos válidos detectados pelo sensor " + sensor.getModelo();
                    eventoService.registrarTransicao(esteira, TipoEventoEsteira.START, estadoAnterior,
                            EstadoEsteira.RUNNING, descInicio);

                    alertaRepository.save(new Alerta("A esteira '" + esteira.getNome() + "' voltou a funcionar.",
                            agora, NivelRisco.NORMAL, esteira));
                }
            }

            // Eventos específicos do tipo de sensor
            if (modoMpu) {
                if (!sensorOkMpu && (laserAnterior || !teveContatoAntes)) {
                    eventoService.registrarTransicao(esteira, TipoEventoEsteira.MPU_FAULT, estadoAnterior,
                            esteira.getEstado(), "Falha de comunicação I2C no sensor MPU-6050 " + sensor.getModelo());
                } else if (sensorOkMpu && nivelRisco != NivelRisco.NORMAL && nivelRiscoAnterior == NivelRisco.NORMAL) {
                    double vib = sensor.getVibracaoRms() != null ? sensor.getVibracaoRms() : 0.0;
                    double giro = sensor.getVelocidadeAngular() != null ? sensor.getVelocidadeAngular() : 0.0;
                    eventoService.registrarTransicao(esteira, TipoEventoEsteira.HIGH_VIBRATION, estadoAnterior,
                            esteira.getEstado(), String.format("Vibração/giro elevado no MPU-6050 %s (VibRMS=%.3fg, Giro=%.1f°/s)",
                                    sensor.getModelo(), vib, giro));
                }
            } else if (valorLegado == null) {
                if (!laser && (laserAnterior || !teveContatoAntes)) {
                    eventoService.registrarTransicao(esteira, TipoEventoEsteira.LASER_LOST, estadoAnterior,
                            esteira.getEstado(), "Feixe de laser interrompido no sensor " + sensor.getModelo() + " (LDR=" + ldr + ")");
                } else if (laser && !laserAnterior && teveContatoAntes) {
                    eventoService.registrarTransicao(esteira, TipoEventoEsteira.LASER_DETECTED, estadoAnterior,
                            esteira.getEstado(), "Feixe de laser restabelecido no sensor " + sensor.getModelo() + " (LDR=" + ldr + ")");
                }
            }
        }

        // 4. Persiste LeituraHistorico no banco
        LeituraHistorico historico = new LeituraHistorico(
                sensor, esteira, ldr, laser, pulso, esteira.getEstado(), pct, nivelRisco, agora
        );
        if (temMpu) {
            historico.aplicarTelemetriaMpu(mpu);
        }
        leituraHistoricoRepository.save(historico);

        // Salva sensor e esteira
        sensorRepository.save(sensor);
        esteiraRepository.save(esteira);

        // 5. Dispara alerta inteligente quando a telemetria entra em ATENCAO ou CRITICO
        if (estadoAnterior != EstadoEsteira.MAINTENANCE) {
            if (nivelRisco != NivelRisco.NORMAL) {
                if (modoMpu) {
                    verificarEDispararAlertaMpu(sensor, nivelRisco, pct, !sensorOkMpu);
                } else {
                    verificarEDispararAlerta(sensor, nivelRisco, pct, !laser && valorLegado == null);
                }
            } else {
                ultimoNivelPorSensor.put(sensor.getId(), NivelRisco.NORMAL);
            }
        }

        // 6. Retorna mapa com formato da resposta da API (compatível + campos MPU-6050)
        Map<String, Object> resposta = new java.util.LinkedHashMap<>();
        resposta.put("success", true);
        resposta.put("sensorId", sensor.getId());
        resposta.put("tipo", sensor.getTipo());
        resposta.put("valor", pct);
        resposta.put("valorLdr", ldr);
        resposta.put("laserDetectado", laser);
        resposta.put("pulsoDetectado", pulso);
        resposta.put("estadoEsteira", esteira.getEstado().name());
        resposta.put("nivelRisco", nivelRisco.name());
        if (temMpu || sensor.isMpu6050()) {
            resposta.put("sensorOk", sensorOkMpu);
            resposta.put("vibracaoRmsG", sensor.getVibracaoRms());
            resposta.put("vibracaoPicoG", sensor.getVibracaoPico());
            resposta.put("gyroPicoDps", sensor.getVelocidadeAngular());
            resposta.put("ax", sensor.getAceleracaoX());
            resposta.put("ay", sensor.getAceleracaoY());
            resposta.put("az", sensor.getAceleracaoZ());
            resposta.put("gx", sensor.getGiroscopioX());
            resposta.put("gy", sensor.getGiroscopioY());
            resposta.put("gz", sensor.getGiroscopioZ());
        }
        return resposta;
    }

    private double calcularIndiceRiscoMpu(Mpu6050Telemetria mpu) {
        double vibRms = mpu.vibracaoRmsG() != null ? mpu.vibracaoRmsG() : 0.0;
        double vibPico = mpu.vibracaoPicoG() != null ? mpu.vibracaoPicoG() : 0.0;
        double vibEfetiva = Math.max(vibRms, vibPico * 0.65);
        double gyro = mpu.gyroPicoDps() != null ? mpu.gyroPicoDps() : 0.0;

        double pctAcel = mapearIndiceMpu(vibEfetiva, 0.06, 0.45, 0.90, 1.50);
        double pctGiro = mapearIndiceMpu(gyro, 8.0, 60.0, 130.0, 220.0);
        return Math.round(Math.max(pctAcel, pctGiro) * 10.0) / 10.0;
    }

    private double mapearIndiceMpu(double val, double limMov, double limAtencao, double limCritico, double limMax) {
        if (val <= 0.0)       return 0.0;
        if (val < limMov)     return interpolar(val, 0.0,        limMov,     0.0,  9.5);
        if (val < limAtencao) return interpolar(val, limMov,     limAtencao, 10.0, 34.0);
        if (val < limCritico) return interpolar(val, limAtencao, limCritico, 35.0, 69.0);
        return interpolar(val, limCritico, limMax, 70.0, 100.0);
    }

    private double interpolar(double x, double xMin, double xMax, double yMin, double yMax) {
        if (x <= xMin) return yMin;
        if (x >= xMax) return yMax;
        return yMin + ((x - xMin) / (xMax - xMin)) * (yMax - yMin);
    }

    // Calcula o nível de risco pela leitura (%)
    public NivelRisco calcularNivel(double leitura) {
        if (leitura >= 70) return NivelRisco.CRITICO;
        if (leitura >= 35) return NivelRisco.ATENCAO;
        return NivelRisco.NORMAL;
    }

    private void verificarEDispararAlertaMpu(Sensor sensor, NivelRisco nivel, double leitura, boolean falhaI2c) {
        if (sensor.getEsteira() == null || sensor.getId() == null) return;

        Long sId = sensor.getId();
        NivelRisco nivelAnterior = ultimoNivelPorSensor.get(sId);
        LocalDateTime ultimoAlerta = ultimoAlertaPorSensor.get(sId);
        LocalDateTime agora = LocalDateTime.now();

        boolean mudouNivel = (nivelAnterior == null || nivelAnterior != nivel);
        boolean tempoExpirou = (ultimoAlerta == null || Duration.between(ultimoAlerta, agora).toMinutes() >= 15);

        if (mudouNivel || tempoExpirou) {
            double vib = sensor.getVibracaoRms() != null ? sensor.getVibracaoRms() : 0.0;
            double giro = sensor.getVelocidadeAngular() != null ? sensor.getVelocidadeAngular() : 0.0;
            String mensagem;
            if (falhaI2c) {
                mensagem = String.format("ALERTA CRÍTICO: Falha de comunicação I2C no sensor MPU-6050 %s da esteira '%s'.",
                        sensor.getModelo(), sensor.getEsteira().getNome());
            } else if (nivel == NivelRisco.CRITICO) {
                mensagem = String.format("ALERTA CRÍTICO: Vibração/impacto severo no MPU-6050 %s da esteira '%s' (VibRMS: %.3fg | Giro: %.1f°/s | Índice: %.1f%%).",
                        sensor.getModelo(), sensor.getEsteira().getNome(), vib, giro, leitura);
            } else {
                mensagem = String.format("ATENÇÃO: Vibração elevada no MPU-6050 %s da esteira '%s' (VibRMS: %.3fg | Giro: %.1f°/s | Índice: %.1f%%).",
                        sensor.getModelo(), sensor.getEsteira().getNome(), vib, giro, leitura);
            }

            alertaRepository.save(new Alerta(mensagem, agora, nivel, sensor.getEsteira()));
            ultimoAlertaPorSensor.put(sId, agora);
            ultimoNivelPorSensor.put(sId, nivel);
        }
    }

    // Gera alerta inteligente (apenas em transições de estado ou a cada 15 min de insistência)
    private void verificarEDispararAlerta(Sensor sensor, NivelRisco nivel, double leitura, boolean falhaLaser) {
        if (sensor.getEsteira() == null || sensor.getId() == null) return;

        Long sId = sensor.getId();
        NivelRisco nivelAnterior = ultimoNivelPorSensor.get(sId);
        LocalDateTime ultimoAlerta = ultimoAlertaPorSensor.get(sId);
        LocalDateTime agora = LocalDateTime.now();

        boolean mudouNivel = (nivelAnterior == null || nivelAnterior != nivel);
        boolean tempoExpirou = (ultimoAlerta == null || Duration.between(ultimoAlerta, agora).toMinutes() >= 15);

        if (mudouNivel || tempoExpirou) {
            String mensagem;
            if (falhaLaser) {
                mensagem = nivel == NivelRisco.CRITICO
                        ? String.format("ALERTA CRÍTICO: Feixe de laser interrompido no sensor %s da esteira '%s' (LDR: %d).",
                        sensor.getModelo(), sensor.getEsteira().getNome(), sensor.getUltimoValorLdr() != null ? sensor.getUltimoValorLdr() : 0)
                        : String.format("ATENÇÃO: Oscilação/interrupção no feixe de laser do sensor %s na esteira '%s' (LDR: %d).",
                        sensor.getModelo(), sensor.getEsteira().getNome(), sensor.getUltimoValorLdr() != null ? sensor.getUltimoValorLdr() : 0);
            } else {
                mensagem = nivel == NivelRisco.CRITICO
                        ? String.format("ALERTA CRÍTICO: Sensor %s na esteira '%s' com risco elevado — leitura: %.1f%%",
                        sensor.getModelo(), sensor.getEsteira().getNome(), leitura)
                        : String.format("Sensor %s na esteira '%s' em nível de ATENÇÃO — leitura: %.1f%%",
                        sensor.getModelo(), sensor.getEsteira().getNome(), leitura);
            }

            alertaRepository.save(new Alerta(mensagem, agora, nivel, sensor.getEsteira()));
            ultimoAlertaPorSensor.put(sId, agora);
            ultimoNivelPorSensor.put(sId, nivel);
        }
    }

    private String getUsuarioAtual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "sistema";
    }
}