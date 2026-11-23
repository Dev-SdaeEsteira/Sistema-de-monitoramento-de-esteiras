package com.cba.sdae.service;

import com.cba.sdae.model.Alerta;
import com.cba.sdae.model.Esteira;
import com.cba.sdae.model.EstadoEsteira;
import com.cba.sdae.model.NivelRisco;
import com.cba.sdae.model.Sensor;
import com.cba.sdae.model.TipoEventoEsteira;
import com.cba.sdae.repository.AlertaRepository;
import com.cba.sdae.repository.EsteiraRepository;
import com.cba.sdae.repository.SensorRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class EsteiraService {

    private final EsteiraRepository    esteiraRepository;
    private final SensorRepository     sensorRepository;
    private final AuditoriaService     auditoriaService;
    private final EventoEsteiraService eventoService;
    private final AlertaRepository     alertaRepository;

    public EsteiraService(EsteiraRepository esteiraRepository,
                          SensorRepository sensorRepository,
                          AuditoriaService auditoriaService,
                          EventoEsteiraService eventoService,
                          AlertaRepository alertaRepository) {
        this.esteiraRepository = esteiraRepository;
        this.sensorRepository  = sensorRepository;
        this.auditoriaService  = auditoriaService;
        this.eventoService     = eventoService;
        this.alertaRepository  = alertaRepository;
    }

    // Retorna todas as esteiras
    public List<Esteira> listarTodas() {
        return esteiraRepository.findAll();
    }

    // Busca uma esteira pelo ID — retorna vazio se não encontrar
    public Optional<Esteira> buscarPorId(Long id) {
        return esteiraRepository.findById(id);
    }

    public long contarTotal()       { return esteiraRepository.count(); }
    public long contarAtivas()      { return esteiraRepository.countByAtiva(true); }
    public long contarInativas()    { return esteiraRepository.countByAtiva(false); }

    // Contadores de estado real
    public long contarFuncionando() { return esteiraRepository.countByEstado(EstadoEsteira.RUNNING); }
    public long contarParadas()     { return esteiraRepository.countByEstado(EstadoEsteira.STOPPED); }
    public long contarOffline()     { return esteiraRepository.countByEstado(EstadoEsteira.OFFLINE); }
    public long contarManutencao()  { return esteiraRepository.countByEstado(EstadoEsteira.MAINTENANCE); }
    public long contarErro()        { return esteiraRepository.countByEstado(EstadoEsteira.ERROR); }

    // Salva uma esteira nova ou atualiza uma existente preservando telemetria e estado
    @Transactional
    public Esteira salvar(Esteira esteira) {
        if (esteira == null
                || esteira.getNome() == null || esteira.getNome().isBlank()
                || esteira.getLocalizacao() == null || esteira.getLocalizacao().isBlank()) {
            throw new IllegalArgumentException("Nome e localização da esteira são obrigatórios.");
        }
        if (esteira.getAtiva() == null) {
            esteira.setAtiva(true);
        }

        boolean isNova = esteira.getId() == null;
        Esteira entidadeParaSalvar = esteira;

        if (!isNova) {
            Optional<Esteira> optExistente = esteiraRepository.findById(esteira.getId());
            if (optExistente.isPresent()) {
                Esteira existente = optExistente.get();
                // Se recebeu uma instância destacada (ex.: formulário MVC), preserva estado, telemetria e metadados
                if (existente != esteira) {
                    existente.setNome(esteira.getNome());
                    existente.setLocalizacao(esteira.getLocalizacao());
                    existente.setAtiva(esteira.getAtiva());
                    if (esteira.getLdrThreshold() != null && !esteira.getLdrThreshold().equals(1800)) {
                        existente.setLdrThreshold(esteira.getLdrThreshold());
                    }
                    if (esteira.getStopTimeoutSeconds() != null && !esteira.getStopTimeoutSeconds().equals(15)) {
                        existente.setStopTimeoutSeconds(esteira.getStopTimeoutSeconds());
                    }
                    if (esteira.getOfflineTimeoutSeconds() != null && !esteira.getOfflineTimeoutSeconds().equals(15)) {
                        existente.setOfflineTimeoutSeconds(esteira.getOfflineTimeoutSeconds());
                    }
                    if (esteira.getMinimumPulses() != null && !esteira.getMinimumPulses().equals(2)) {
                        existente.setMinimumPulses(esteira.getMinimumPulses());
                    }
                    if (esteira.getSampleIntervalMs() != null && !esteira.getSampleIntervalMs().equals(50)) {
                        existente.setSampleIntervalMs(esteira.getSampleIntervalMs());
                    }
                }
                entidadeParaSalvar = existente;
            }
        }

        if (entidadeParaSalvar.getEstado() == null) {
            entidadeParaSalvar.setEstado(EstadoEsteira.STOPPED);
        }

        Esteira salva = esteiraRepository.save(entidadeParaSalvar);

        // Registrar auditoria
        String usuario = getUsuarioAtual();
        String acao = isNova ? "CRIAR" : "ATUALIZAR";
        String detalhes = String.format("Nome: %s, Localização: %s, Ativa: %s, Estado: %s",
            salva.getNome(), salva.getLocalizacao(), salva.getAtiva(), salva.getEstado());
        auditoriaService.registrar(acao, "ESTEIRA", salva.getId(), usuario, detalhes);

        return salva;
    }

    @Transactional
    public Esteira alternarManutencao(Long id, boolean entrar, String motivo) {
        return buscarPorId(id).map(esteira -> {
            EstadoEsteira anterior = esteira.getEstado();
            String usuario = getUsuarioAtual();
            LocalDateTime agora = LocalDateTime.now();

            if (entrar) {
                esteira.setEstado(EstadoEsteira.MAINTENANCE);
                eventoService.registrarTransicao(esteira, TipoEventoEsteira.MAINTENANCE_START,
                        anterior, EstadoEsteira.MAINTENANCE, "Manutenção iniciada por " + usuario + ": " + (motivo != null ? motivo : "Operação programada"));
                alertaRepository.save(new Alerta("Esteira '" + esteira.getNome() + "' colocada em MANUTENÇÃO por " + usuario + ".",
                        agora, NivelRisco.ATENCAO, esteira));
                auditoriaService.registrar("MANUTENCAO_INICIO", "ESTEIRA", id, usuario, "Motivo: " + motivo);
            } else {
                esteira.setEstado(EstadoEsteira.STOPPED);
                esteira.setTempoParadaInicio(agora);
                eventoService.registrarTransicao(esteira, TipoEventoEsteira.MAINTENANCE_END,
                        anterior, EstadoEsteira.STOPPED, "Manutenção encerrada por " + usuario);
                alertaRepository.save(new Alerta("Manutenção da esteira '" + esteira.getNome() + "' encerrada.",
                        agora, NivelRisco.NORMAL, esteira));
                auditoriaService.registrar("MANUTENCAO_FIM", "ESTEIRA", id, usuario, "Finalizada com sucesso");
            }
            return esteiraRepository.save(esteira);
        }).orElseThrow(() -> new IllegalArgumentException("Esteira não encontrada: " + id));
    }

    // Soft-delete uma esteira pelo ID e aplica soft-delete em cascata nos sensores vinculados
    @Transactional
    public void deletar(Long id) {
        esteiraRepository.findById(id).ifPresent(esteira -> {
            String usuario = getUsuarioAtual();
            LocalDateTime agora = LocalDateTime.now();

            List<Sensor> sensoresVinculados = sensorRepository.findByEsteira_Id(id);
            for (Sensor sensor : sensoresVinculados) {
                sensor.setExcluidoEm(agora);
                sensor.setExcluidoPor(usuario);
                sensorRepository.save(sensor);
                auditoriaService.registrar("EXCLUIR", "SENSOR", sensor.getId(), usuario,
                        String.format("Excluído em cascata com a esteira '%s' (ID %d)", esteira.getNome(), id));
            }

            esteira.setExcluidoEm(agora);
            esteira.setExcluidoPor(usuario);
            esteiraRepository.save(esteira);

            // Registrar auditoria
            String detalhes = String.format("Nome: %s, Localização: %s, Sensores removidos: %d",
                esteira.getNome(), esteira.getLocalizacao(), sensoresVinculados.size());
            auditoriaService.registrar("EXCLUIR", "ESTEIRA", id, usuario, detalhes);
        });
    }

    private String getUsuarioAtual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "sistema";
    }
}