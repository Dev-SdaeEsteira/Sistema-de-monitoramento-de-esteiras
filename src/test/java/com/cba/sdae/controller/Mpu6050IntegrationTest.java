package com.cba.sdae.controller;

import com.cba.sdae.BaseIntegrationTest;
import com.cba.sdae.model.*;
import com.cba.sdae.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@DisplayName("Testes de Integração MPU-6050 (Backend + API + Telas)")
class Mpu6050IntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private EsteiraRepository esteiraRepository;

    @Autowired
    private AlertaRepository alertaRepository;

    @Autowired
    private EventoEsteiraRepository eventoRepository;

    @Autowired
    private LeituraHistoricoRepository leituraRepo;

    private Esteira esteira;
    private Sensor sensorMpu;
    private static final String API_KEY = "CHAVE-MPU6050-TESTE-01";

    @BeforeEach
    void setUp() {
        leituraRepo.deleteAll();
        eventoRepository.deleteAll();
        alertaRepository.deleteAll();
        sensorRepository.deleteAll();
        esteiraRepository.deleteAll();

        esteira = new Esteira("Esteira MPU Principal", "Linha 02", true);
        esteira.setEstado(EstadoEsteira.STOPPED);
        esteira = esteiraRepository.save(esteira);

        sensorMpu = new Sensor();
        sensorMpu.setModelo("ESP32-MPU6050-01");
        sensorMpu.setTipo("MPU-6050 (Acelerômetro + Giroscópio)");
        sensorMpu.setEsteira(esteira);
        sensorMpu.setApiKey(API_KEY);
        sensorMpu = sensorRepository.save(sensorMpu);
    }

    @Test
    @DisplayName("POST /api/sensores/{id}/leitura com payload MPU-6050 nativo deve persistir 6 eixos e mudar esteira para RUNNING")
    void leituraMpu6050Nativa_devePersistirSeisEixosEColocarEmRunning() throws Exception {
        String payload = """
            {
                "vibracaoRmsG": 0.145,
                "vibracaoPicoG": 0.210,
                "gyroPicoDps": 14.2,
                "ax": 0.02,
                "ay": -0.01,
                "az": 1.00,
                "gx": 1.2,
                "gy": -0.8,
                "gz": 14.2,
                "sensorOk": true
            }
        """;

        mockMvc.perform(post("/api/sensores/" + sensorMpu.getId() + "/leitura")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.pulsoDetectado").value(true))
                .andExpect(jsonPath("$.estadoEsteira").value("RUNNING"))
                .andExpect(jsonPath("$.nivelRisco").value("NORMAL"))
                .andExpect(jsonPath("$.vibracaoRmsG").value(0.145))
                .andExpect(jsonPath("$.gyroPicoDps").value(14.2))
                .andExpect(jsonPath("$.az").value(1.0));

        Sensor atualizado = sensorRepository.findById(sensorMpu.getId()).orElseThrow();
        assertThat(atualizado.isMpu6050()).isTrue();
        assertThat(atualizado.getVibracaoRms()).isEqualTo(0.145);
        assertThat(atualizado.getVibracaoPico()).isEqualTo(0.210);
        assertThat(atualizado.getVelocidadeAngular()).isEqualTo(14.2);
        assertThat(atualizado.getAceleracaoZ()).isEqualTo(1.00);
        assertThat(atualizado.getGiroscopioZ()).isEqualTo(14.2);

        List<LeituraHistorico> hist = leituraRepo.findBySensor_IdOrderByDataHoraDesc(
                sensorMpu.getId(), PageRequest.of(0, 5));
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0).isTemDadosMpu6050()).isTrue();
        assertThat(hist.get(0).getVibracaoRms()).isEqualTo(0.145);
        assertThat(hist.get(0).getGiroscopioZ()).isEqualTo(14.2);
    }

    @Test
    @DisplayName("Vibração severa no MPU-6050 deve gerar nível CRITICO, evento HIGH_VIBRATION e alerta")
    void vibracaoSeveraMpu6050_deveGerarEventoEAlertaCritico() throws Exception {
        String payload = """
            {
                "vibracaoRmsG": 1.150,
                "vibracaoPicoG": 1.420,
                "gyroPicoDps": 145.0,
                "ax": 0.85,
                "ay": -0.40,
                "az": 1.32,
                "gx": 42.0,
                "gy": 35.0,
                "gz": 145.0,
                "pulsoDetectado": true,
                "sensorOk": true
            }
        """;

        mockMvc.perform(post("/api/sensores/" + sensorMpu.getId() + "/leitura")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nivelRisco").value("CRITICO"));

        List<EventoEsteira> eventos = eventoRepository.findByEsteira_IdOrderByDataHoraInicioDesc(
                esteira.getId(), PageRequest.of(0, 10));
        assertThat(eventos).anyMatch(ev -> ev.getTipoEvento() == TipoEventoEsteira.HIGH_VIBRATION);

        List<Alerta> alertas = alertaRepository.findByEsteira_Id(esteira.getId());
        assertThat(alertas).anyMatch(a -> a.getNivelRisco() == NivelRisco.CRITICO
                && a.getMensagem().contains("MPU-6050"));
    }

    @Test
    @DisplayName("Falha I2C (sensorOk=false) no MPU-6050 deve gerar evento MPU_FAULT e alerta crítico")
    void falhaI2cMpu6050_deveGerarMpuFaultEAlertaCritico() throws Exception {
        String payload = """
            {
                "sensorOk": false,
                "pulsoDetectado": false
            }
        """;

        mockMvc.perform(post("/api/sensores/" + sensorMpu.getId() + "/leitura")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nivelRisco").value("CRITICO"))
                .andExpect(jsonPath("$.sensorOk").value(false));

        List<EventoEsteira> eventos = eventoRepository.findByEsteira_IdOrderByDataHoraInicioDesc(
                esteira.getId(), PageRequest.of(0, 10));
        assertThat(eventos).anyMatch(ev -> ev.getTipoEvento() == TipoEventoEsteira.MPU_FAULT);

        List<Alerta> alertas = alertaRepository.findByEsteira_Id(esteira.getId());
        assertThat(alertas).anyMatch(a -> a.getNivelRisco() == NivelRisco.CRITICO
                && a.getMensagem().contains("I2C"));
    }

    @Test
    @WithMockUser(username = "engenheiro", roles = "ENGENHEIRO")
    @DisplayName("Dashboard API e telas MVC devem expor e renderizar telemetria MPU-6050 sem erros")
    void dashboardETelasMvc_devemRenderizarDadosMpu6050() throws Exception {
        String payload = """
            {
                "vibracaoRmsG": 0.185,
                "vibracaoPicoG": 0.250,
                "gyroPicoDps": 18.5,
                "ax": 0.03,
                "ay": -0.02,
                "az": 1.01,
                "gx": 2.0,
                "gy": -1.5,
                "gz": 18.5,
                "pulsoDetectado": true,
                "sensorOk": true
            }
        """;

        mockMvc.perform(post("/api/sensores/" + sensorMpu.getId() + "/leitura")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", API_KEY)
                        .content(payload))
                .andExpect(status().isOk());

        // API Dashboard + gráfico
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.esteiras[0].sensorMpu6050").value(true))
                .andExpect(jsonPath("$.esteiras[0].sensorVibracaoRms").value(0.185))
                .andExpect(jsonPath("$.esteiras[0].sensorVelocidadeAngular").value(18.5));

        mockMvc.perform(get("/api/dashboard/grafico-ldr").param("periodo", "15m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].temDadosMpu6050").value(true))
                .andExpect(jsonPath("$[0].vibracaoRms").value(0.185))
                .andExpect(jsonPath("$[0].velocidadeAngular").value(18.5));

        // Telas MVC
        mockMvc.perform(get("/sensores"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ESP32-MPU6050-01")))
                .andExpect(content().string(containsString("Vibração RMS")));

        mockMvc.perform(get("/sensores/detalhe/" + sensorMpu.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Telemetria 6 Eixos — MPU-6050")));

        mockMvc.perform(get("/sensores/novo"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MPU-6050 (Acelerômetro + Giroscópio)")));

        mockMvc.perform(get("/esteiras/detalhe/" + esteira.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sensor Vinculado (MPU-6050)")));

        mockMvc.perform(get("/leituras"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ESP32-MPU6050-01")));

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Esteira MPU Principal")));
    }

    @Test
    @WithMockUser(username = "admin@sdae.com.br", roles = "ADMINISTRADOR")
    @DisplayName("Todas as telas front-end (públicas e autenticadas) devem renderizar 200 OK sem erro de template")
    void todasAsTelasFrontend_devemRenderizar200() throws Exception {
        String[] rotas = {
                "/welcome",
                "/login",
                "/cadastro",
                "/dashboard",
                "/esteiras",
                "/esteiras/nova",
                "/esteiras/editar/" + esteira.getId(),
                "/esteiras/detalhe/" + esteira.getId(),
                "/sensores",
                "/sensores/novo",
                "/sensores/editar/" + sensorMpu.getId(),
                "/sensores/detalhe/" + sensorMpu.getId(),
                "/leituras",
                "/relatorios",
                "/perfil",
                "/admin",
                "/admin/novo"
        };

        for (String rota : rotas) {
            mockMvc.perform(get(rota))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("<!DOCTYPE html>")));
        }
    }
}
