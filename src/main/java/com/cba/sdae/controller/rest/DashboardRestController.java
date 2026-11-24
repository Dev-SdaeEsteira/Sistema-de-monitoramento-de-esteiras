package com.cba.sdae.controller.rest;

import com.cba.sdae.model.Esteira;
import com.cba.sdae.model.LeituraHistorico;
import com.cba.sdae.model.NivelRisco;
import com.cba.sdae.model.Sensor;
import com.cba.sdae.repository.LeituraHistoricoRepository;
import com.cba.sdae.service.AlertaService;
import com.cba.sdae.service.EsteiraService;
import com.cba.sdae.service.EventoEsteiraService;
import com.cba.sdae.service.SensorService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;

/**
 * GET /api/dashboard
 * Retorna KPIs em tempo real, esteiras, sensores, eventos e leituras diretamente do banco.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardRestController {

    private final EsteiraService             esteiraService;
    private final SensorService              sensorService;
    private final AlertaService              alertaService;
    private final EventoEsteiraService       eventoService;
    private final LeituraHistoricoRepository leituraRepo;

    public DashboardRestController(EsteiraService esteiraService,
                                   SensorService sensorService,
                                   AlertaService alertaService,
                                   EventoEsteiraService eventoService,
                                   LeituraHistoricoRepository leituraRepo) {
        this.esteiraService = esteiraService;
        this.sensorService  = sensorService;
        this.alertaService  = alertaService;
        this.eventoService  = eventoService;
        this.leituraRepo    = leituraRepo;
    }

    @GetMapping
    public Map<String, Object> stats() {
        long totalEsteiras        = esteiraService.contarTotal();
        long esteirasAtivas       = esteiraService.contarAtivas();
        long esteirasFuncionando  = esteiraService.contarFuncionando();
        long esteirasParadas      = esteiraService.contarParadas();
        long esteirasOffline      = esteiraService.contarOffline();
        long esteirasManutencao   = esteiraService.contarManutencao();

        long totalSensores        = sensorService.contarTotal();
        long sensoresCriticos     = sensorService.contarPorNivelRisco(NivelRisco.CRITICO);
        long totalAlertas         = alertaService.contarTotal();
        long alertasCriticos      = alertaService.contarPorNivelRisco(NivelRisco.CRITICO);
        double maxRisco           = sensorService.buscarMaiorLeitura();
        var ultimosAlertas        = alertaService.listarMaisRecentes(10);
        var ultimosEventos        = eventoService.listarMaisRecentes(10);

        List<Esteira> listaEsteiras = esteiraService.listarTodas();
        List<Map<String, Object>> esteirasDto = new ArrayList<>();

        for (Esteira e : listaEsteiras) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", e.getId());
            map.put("nome", e.getNome());
            map.put("localizacao", e.getLocalizacao());
            map.put("ativa", e.getAtiva());
            map.put("estado", e.getEstado().name());
            map.put("estadoDescricao", e.getEstado().getDescricao());
            map.put("ldrThreshold", e.getLdrThreshold());
            map.put("stopTimeoutSeconds", e.getStopTimeoutSeconds());
            map.put("offlineTimeoutSeconds", e.getOfflineTimeoutSeconds());
            map.put("tempoParadoFormatado", e.getTempoParadoFormatado());
            map.put("tempoFuncionandoFormatado", e.getTempoFuncionandoFormatado());
            map.put("ultimoPulsoTexto", e.getUltimoPulsoTexto());
            map.put("ultimaComunicacaoTexto", e.getUltimaComunicacaoTexto());

            // Sensor vinculado principal
            List<Sensor> sensList = e.getSensores();
            if (sensList != null && !sensList.isEmpty()) {
                Sensor prim = sensList.get(0);
                map.put("sensorId", prim.getId());
                map.put("sensorNome", prim.getModelo());
                map.put("sensorTipo", prim.getTipo());
                map.put("sensorMpu6050", prim.isMpu6050());
                map.put("sensorValorLdr", prim.getUltimoValorLdr());
                map.put("sensorLeituraAtual", prim.getLeituraAtual());
                map.put("sensorLaser", prim.getLaserDetectado());
                map.put("sensorPulsos", prim.getQuantidadePulsos());
                map.put("sensorConectado", prim.isConectado());
                map.put("sensorUltimaLeituraTexto", prim.getUltimaLeituraTexto());
                map.put("sensorNivelRisco", prim.getNivelRisco().name());
                map.put("sensorVibracaoRms", prim.getVibracaoRms());
                map.put("sensorVibracaoPico", prim.getVibracaoPico());
                map.put("sensorVelocidadeAngular", prim.getVelocidadeAngular());
                map.put("sensorAx", prim.getAceleracaoX());
                map.put("sensorAy", prim.getAceleracaoY());
                map.put("sensorAz", prim.getAceleracaoZ());
                map.put("sensorGx", prim.getGiroscopioX());
                map.put("sensorGy", prim.getGiroscopioY());
                map.put("sensorGz", prim.getGiroscopioZ());
            } else {
                map.put("sensorNome", "Sem sensor");
                map.put("sensorMpu6050", false);
                map.put("sensorValorLdr", 0);
                map.put("sensorLeituraAtual", 0.0);
                map.put("sensorLaser", false);
                map.put("sensorPulsos", 0);
                map.put("sensorConectado", false);
                map.put("sensorUltimaLeituraTexto", "—");
                map.put("sensorNivelRisco", "NORMAL");
            }
            esteirasDto.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("totalEsteiras",       totalEsteiras);
        response.put("esteirasAtivas",      esteirasAtivas);
        response.put("esteirasFuncionando", esteirasFuncionando);
        response.put("esteirasParadas",     esteirasParadas);
        response.put("esteirasOffline",     esteirasOffline);
        response.put("esteirasManutencao",  esteirasManutencao);
        response.put("totalSensores",       totalSensores);
        response.put("sensoresCriticos",    sensoresCriticos);
        response.put("totalAlertas",        totalAlertas);
        response.put("alertasCriticos",     alertasCriticos);
        response.put("nivelRiscoAtual",     (int) maxRisco);
        response.put("esteiras",            esteirasDto);
        response.put("ultimosAlertas",      ultimosAlertas);
        response.put("ultimosEventos",      ultimosEventos);

        return response;
    }

    /**
     * Endpoint para dados do gráfico histórico (MPU-6050 e LDR).
     * Suporta períodos: 5m, 15m, 1h, 24h.
     */
    @GetMapping("/grafico-ldr")
    public List<Map<String, Object>> graficoLdr(
            @RequestParam(required = false) Long esteiraId,
            @RequestParam(required = false) Long sensorId,
            @RequestParam(defaultValue = "15m") String periodo) {

        LocalDateTime inicio = switch (periodo.toLowerCase()) {
            case "5m"  -> LocalDateTime.now().minusMinutes(5);
            case "1h"  -> LocalDateTime.now().minusHours(1);
            case "24h" -> LocalDateTime.now().minusHours(24);
            default    -> LocalDateTime.now().minusMinutes(15);
        };

        PageRequest limite = PageRequest.of(0, 200);
        List<LeituraHistorico> leituras;
        if (sensorId != null) {
            leituras = new ArrayList<>(leituraRepo.findBySensor_IdAndDataHoraAfterOrderByDataHoraDesc(sensorId, inicio, limite));
        } else if (esteiraId != null) {
            leituras = new ArrayList<>(leituraRepo.findByEsteira_IdAndDataHoraAfterOrderByDataHoraDesc(esteiraId, inicio, limite));
        } else {
            leituras = new ArrayList<>(leituraRepo.findByDataHoraAfterOrderByDataHoraDesc(inicio, limite));
        }
        Collections.reverse(leituras);

        List<Map<String, Object>> result = new ArrayList<>();
        for (LeituraHistorico l : leituras) {
            Map<String, Object> ponto = new LinkedHashMap<>();
            ponto.put("hora", l.getDataHora().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            ponto.put("valorLdr", l.getValorLdr() != null ? l.getValorLdr() : 0);
            ponto.put("laser", l.getLaserDetectado() != null && l.getLaserDetectado());
            ponto.put("pulso", l.getPulsoDetectado() != null && l.getPulsoDetectado());
            ponto.put("estado", l.getEstadoEsteira() != null ? l.getEstadoEsteira().name() : "STOPPED");
            ponto.put("porcentagem", l.getValor() != null ? l.getValor() : 0.0);
            ponto.put("temDadosMpu6050", l.isTemDadosMpu6050());
            ponto.put("vibracaoRms", l.getVibracaoRms());
            ponto.put("vibracaoPico", l.getVibracaoPico());
            ponto.put("velocidadeAngular", l.getVelocidadeAngular());
            ponto.put("ax", l.getAceleracaoX());
            ponto.put("ay", l.getAceleracaoY());
            ponto.put("az", l.getAceleracaoZ());
            ponto.put("gx", l.getGiroscopioX());
            ponto.put("gy", l.getGiroscopioY());
            ponto.put("gz", l.getGiroscopioZ());
            result.add(ponto);
        }
        return result;
    }
}