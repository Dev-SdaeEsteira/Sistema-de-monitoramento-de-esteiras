package com.cba.sdae.model;

/**
 * Dados de telemetria enviados pelo módulo MPU-6050 (Acelerômetro 3 eixos em g + Giroscópio 3 eixos em °/s).
 */
public record Mpu6050Telemetria(
        Double ax,
        Double ay,
        Double az,
        Double gx,
        Double gy,
        Double gz,
        Double vibracaoRmsG,
        Double vibracaoPicoG,
        Double gyroPicoDps,
        Boolean sensorOk
) {
    public boolean temDados() {
        return ax != null || ay != null || az != null
                || gx != null || gy != null || gz != null
                || vibracaoRmsG != null || vibracaoPicoG != null || gyroPicoDps != null
                || sensorOk != null;
    }
}
