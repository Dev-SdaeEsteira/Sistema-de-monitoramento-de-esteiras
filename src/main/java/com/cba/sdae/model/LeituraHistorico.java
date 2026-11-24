package com.cba.sdae.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import java.time.LocalDateTime;

/**
 * Persiste cada leitura enviada por um sensor.
 * Endpoint: POST /api/sensores/{id}/leitura
 */
@Entity
@Table(name = "leituras_historico")
public class LeituraHistorico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    @JsonIgnore
    private Sensor sensor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "esteira_id")
    @NotFound(action = NotFoundAction.IGNORE)
    @JsonIgnore
    private Esteira esteira;

    // Valor LDR bruto (0 a 4095)
    @Column(name = "valor_ldr")
    private Integer valorLdr;

    // Laser detectado (true se feixe presente, false se interrompido)
    @Column(name = "laser_detectado")
    private Boolean laserDetectado = false;

    // Pulso detectado neste ciclo
    @Column(name = "pulso_detectado")
    private Boolean pulsoDetectado = false;

    // Estado da esteira no momento da leitura
    @Enumerated(EnumType.STRING)
    @Column(name = "estado_esteira")
    private EstadoEsteira estadoEsteira = EstadoEsteira.STOPPED;

    // DataHora da leitura
    @Column(name = "data_hora", nullable = false)
    private LocalDateTime dataHora;

    // Campos legados mantidos para compatibilidade total
    @Column(nullable = false)
    private Double valor = 0.0;          // leitura em %

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NivelRisco nivelRisco = NivelRisco.NORMAL;

    // Telemetria MPU-6050 (Acelerômetro em g + Giroscópio em °/s)
    @Column(name = "aceleracao_x")
    private Double aceleracaoX;

    @Column(name = "aceleracao_y")
    private Double aceleracaoY;

    @Column(name = "aceleracao_z")
    private Double aceleracaoZ;

    @Column(name = "giroscopio_x")
    private Double giroscopioX;

    @Column(name = "giroscopio_y")
    private Double giroscopioY;

    @Column(name = "giroscopio_z")
    private Double giroscopioZ;

    @Column(name = "vibracao_rms")
    private Double vibracaoRms;

    @Column(name = "vibracao_pico")
    private Double vibracaoPico;

    @Column(name = "velocidade_angular")
    private Double velocidadeAngular;

    // ── Construtores ──────────────────────────────────────────────
    public LeituraHistorico() {}

    public LeituraHistorico(Double valor, NivelRisco nivelRisco,
                            LocalDateTime dataHora, Sensor sensor) {
        this.valor      = valor;
        this.nivelRisco = nivelRisco;
        this.dataHora   = dataHora;
        this.sensor     = sensor;
        if (sensor != null) {
            this.esteira = sensor.getEsteira();
            if (sensor.getEsteira() != null) {
                this.estadoEsteira = sensor.getEsteira().getEstado();
            }
            this.valorLdr = sensor.getUltimoValorLdr();
            this.laserDetectado = sensor.getLaserDetectado();
        }
    }

    public LeituraHistorico(Sensor sensor, Esteira esteira, Integer valorLdr, Boolean laserDetectado,
                            Boolean pulsoDetectado, EstadoEsteira estadoEsteira, Double valor,
                            NivelRisco nivelRisco, LocalDateTime dataHora) {
        this.sensor = sensor;
        this.esteira = esteira;
        this.valorLdr = valorLdr;
        this.laserDetectado = laserDetectado;
        this.pulsoDetectado = pulsoDetectado;
        this.estadoEsteira = estadoEsteira;
        this.valor = valor != null ? valor : 0.0;
        this.nivelRisco = nivelRisco != null ? nivelRisco : NivelRisco.NORMAL;
        this.dataHora = dataHora != null ? dataHora : LocalDateTime.now();
    }

    // ── Getters e Setters ─────────────────────────────────────────
    public Long getId()                        { return id; }
    public void setId(Long id)                 { this.id = id; }

    public Sensor getSensor()                  { return sensor; }
    public void setSensor(Sensor sensor)       { this.sensor = sensor; }

    public Esteira getEsteira()                { return esteira; }
    public void setEsteira(Esteira esteira)    { this.esteira = esteira; }

    public Integer getValorLdr()               { return valorLdr; }
    public void setValorLdr(Integer valorLdr)  { this.valorLdr = valorLdr; }

    public Boolean getLaserDetectado()         { return laserDetectado; }
    public void setLaserDetectado(Boolean l)   { this.laserDetectado = l; }

    public Boolean getPulsoDetectado()         { return pulsoDetectado; }
    public void setPulsoDetectado(Boolean p)   { this.pulsoDetectado = p; }

    public EstadoEsteira getEstadoEsteira()    { return estadoEsteira; }
    public void setEstadoEsteira(EstadoEsteira e) { this.estadoEsteira = e; }

    public LocalDateTime getDataHora()         { return dataHora; }
    public void setDataHora(LocalDateTime d)   { this.dataHora = d; }

    public Double getValor()                   { return valor; }
    public void setValor(Double valor)         { this.valor = valor; }

    public NivelRisco getNivelRisco()          { return nivelRisco; }
    public void setNivelRisco(NivelRisco n)    { this.nivelRisco = n; }

    public Double getAceleracaoX()             { return aceleracaoX; }
    public void setAceleracaoX(Double ax)      { this.aceleracaoX = ax; }

    public Double getAceleracaoY()             { return aceleracaoY; }
    public void setAceleracaoY(Double ay)      { this.aceleracaoY = ay; }

    public Double getAceleracaoZ()             { return aceleracaoZ; }
    public void setAceleracaoZ(Double az)      { this.aceleracaoZ = az; }

    public Double getGiroscopioX()             { return giroscopioX; }
    public void setGiroscopioX(Double gx)      { this.giroscopioX = gx; }

    public Double getGiroscopioY()             { return giroscopioY; }
    public void setGiroscopioY(Double gy)      { this.giroscopioY = gy; }

    public Double getGiroscopioZ()             { return giroscopioZ; }
    public void setGiroscopioZ(Double gz)      { this.giroscopioZ = gz; }

    public Double getVibracaoRms()             { return vibracaoRms; }
    public void setVibracaoRms(Double v)       { this.vibracaoRms = v; }

    public Double getVibracaoPico()            { return vibracaoPico; }
    public void setVibracaoPico(Double v)      { this.vibracaoPico = v; }

    public Double getVelocidadeAngular()       { return velocidadeAngular; }
    public void setVelocidadeAngular(Double v) { this.velocidadeAngular = v; }

    public void aplicarTelemetriaMpu(Mpu6050Telemetria mpu) {
        if (mpu == null) return;
        this.aceleracaoX       = mpu.ax();
        this.aceleracaoY       = mpu.ay();
        this.aceleracaoZ       = mpu.az();
        this.giroscopioX       = mpu.gx();
        this.giroscopioY       = mpu.gy();
        this.giroscopioZ       = mpu.gz();
        this.vibracaoRms       = mpu.vibracaoRmsG();
        this.vibracaoPico      = mpu.vibracaoPicoG();
        this.velocidadeAngular = mpu.gyroPicoDps();
    }

    @JsonProperty("temDadosMpu6050")
    public boolean isTemDadosMpu6050() {
        return vibracaoRms != null || aceleracaoX != null || velocidadeAngular != null;
    }

    @JsonProperty("sensorId")
    public Long getSensorId() {
        return sensor != null ? sensor.getId() : null;
    }

    @JsonProperty("sensorModelo")
    public String getSensorModelo() {
        return sensor != null ? sensor.getModelo() : null;
    }

    @JsonProperty("esteiraId")
    public Long getEsteiraId() {
        return esteira != null ? esteira.getId() : (sensor != null && sensor.getEsteira() != null ? sensor.getEsteira().getId() : null);
    }

    @JsonProperty("esteiraNome")
    public String getEsteiraNome() {
        return esteira != null ? esteira.getNome() : (sensor != null && sensor.getEsteira() != null ? sensor.getEsteira().getNome() : null);
    }

    @JsonProperty("dataHoraFormatada")
    public String getDataHoraFormatada() {
        if (dataHora == null) return "—";
        return dataHora.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
    }
}