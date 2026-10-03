package org.example.transferencia.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.example.transferencia.domain.StatusTransferencia;
import org.example.transferencia.domain.Transferencia;
import org.example.transferencia.domain.TransferenciaRepository;
import org.example.transferencia.infrastructure.outbox.OutboxMessage;
import org.example.transferencia.infrastructure.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransferenciaProcessManager {

    private static final Logger log = LoggerFactory.getLogger(TransferenciaProcessManager.class);

    private final TransferenciaRepository transferenciaRepository;
    private final OutboxRepository outboxRepository;
    private final ContaCommandClient contaCommandClient;
    private final ObjectMapper objectMapper;

    public TransferenciaProcessManager(
            TransferenciaRepository transferenciaRepository,
            OutboxRepository outboxRepository,
            ContaCommandClient contaCommandClient,
            ObjectMapper objectMapper
    ) {
        this.transferenciaRepository = transferenciaRepository;
        this.outboxRepository = outboxRepository;
        this.contaCommandClient = contaCommandClient;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "conta-events", groupId = "transferencia-group")
    @Transactional
    public void processarEventoConta(String mensagemJson) {
        log.info("ProcessManager recebeu evento do Kafka: {}", mensagemJson);
        try {
            JsonNode root = objectMapper.readTree(mensagemJson);
            String eventType = root.has("eventType") ? root.get("eventType").asText() : "";

            // Fallback para inferir tipo do evento caso payload legado venha sem eventType
            if (eventType == null || eventType.isBlank()) {
                if (root.has("motivo")) {
                    eventType = "DebitoRejeitado";
                } else if (root.has("chaveIdempotencia")) {
                    String chave = root.get("chaveIdempotencia").asText();
                    if (chave.endsWith("-debito")) {
                        eventType = "ContaDebitada";
                    } else if (chave.endsWith("-credito") || chave.endsWith("-compensacao")) {
                        eventType = "ContaCreditada";
                    }
                }
            }

            Long transferenciaId = root.has("transferenciaId") ? root.get("transferenciaId").asLong() : null;

            if (transferenciaId == null) {
                log.warn("Evento sem correlationId (transferenciaId): {}", mensagemJson);
                return;
            }

            Transferencia transferencia = transferenciaRepository.findById(transferenciaId).orElse(null);
            if (transferencia == null) {
                log.warn("Transferencia {} nao encontrada para o evento {}", transferenciaId, eventType);
                return;
            }

            log.info("Processando evento {} para transferencia {} (status atual: {})",
                    eventType, transferenciaId, transferencia.getStatus());

            switch (eventType) {
                case "ContaDebitada" -> aoReceberContaDebitada(transferencia);
                case "DebitoRejeitado" -> aoReceberDebitoRejeitado(transferencia, root.path("motivo").asText("Saldo insuficiente"));
                case "ContaCreditada" -> aoReceberContaCreditada(transferencia);
                case "CreditoRejeitado" -> aoReceberCreditoRejeitado(transferencia, root.path("motivo").asText("Erro ao creditar"));
                default -> log.debug("Evento ignorado pelo ProcessManager: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Erro ao processar mensagem do Kafka: {}", e.getMessage(), e);
        }
    }

    public void aoReceberContaDebitada(Transferencia transferencia) {
        if (transferencia.getStatus() != StatusTransferencia.INICIADA) {
            log.warn("Transferencia {} ja em estado {}, ignorando ContaDebitada", transferencia.getId(), transferencia.getStatus());
            return;
        }
        log.info("Saga passo 1 concluido: confirmando debito para transferencia {}", transferencia.getId());
        transferencia.confirmarDebito();
        salvarEGravarOutbox(transferencia);

        String chaveIdempotencia = "transferencia-" + transferencia.getId() + "-credito";
        try {
            contaCommandClient.creditar(
                    transferencia.getContaDestinoId(),
                    transferencia.getValor(),
                    chaveIdempotencia,
                    transferencia.getId()
            );
        } catch (Exception e) {
            log.error("Falha ao invocar credito para transferencia {}: {}. Iniciando compensacao.", transferencia.getId(), e.getMessage());
            iniciarCompensacao(transferencia, e.getMessage());
        }
    }

    public void aoReceberDebitoRejeitado(Transferencia transferencia, String motivo) {
        if (transferencia.getStatus() == StatusTransferencia.INICIADA) {
            log.info("Debito rejeitado para transferencia {}: {}", transferencia.getId(), motivo);
            transferencia.falharDebito();
            transferenciaRepository.save(transferencia);
        }
    }

    public void aoReceberContaCreditada(Transferencia transferencia) {
        if (transferencia.getStatus() == StatusTransferencia.CONTA_ORIGEM_DEBITADA) {
            log.info("Saga passo 2 concluido: confirmando credito para transferencia {}", transferencia.getId());
            transferencia.confirmarCredito();
            salvarEGravarOutbox(transferencia);
            log.info("Transferencia {} CONCLUIDA com sucesso!", transferencia.getId());
        } else if (transferencia.getStatus() == StatusTransferencia.CREDITO_FALHOU) {
            log.info("Saga compensacao concluida para transferencia {}", transferencia.getId());
            transferencia.confirmarCompensacao();
            salvarEGravarOutbox(transferencia);
            log.info("Transferencia {} COMPENSADA com sucesso!", transferencia.getId());
        }
    }

    public void aoReceberCreditoRejeitado(Transferencia transferencia, String motivo) {
        iniciarCompensacao(transferencia, motivo);
    }

    public void iniciarCompensacao(Transferencia transferencia, String motivo) {
        if (transferencia.getStatus() == StatusTransferencia.CONTA_ORIGEM_DEBITADA
                || transferencia.getStatus() == StatusTransferencia.INICIADA) {
            log.warn("Credito falhou ou timeout para transferencia {}. Transicionando para CREDITO_FALHOU e compensando.", transferencia.getId());
            if (transferencia.getStatus() == StatusTransferencia.INICIADA) {
                transferencia.confirmarDebito();
            }
            transferencia.falharCredito(motivo);
            salvarEGravarOutbox(transferencia);

            String chaveCompensacao = "transferencia-" + transferencia.getId() + "-compensacao";
            try {
                contaCommandClient.creditar(
                        transferencia.getContaOrigemId(),
                        transferencia.getValor(),
                        chaveCompensacao,
                        transferencia.getId()
                );
            } catch (Exception ex) {
                log.error("FALHA CRITICA na compensacao da transferencia {}: {}", transferencia.getId(), ex.getMessage());
                transferencia.falharCompensacao();
                transferenciaRepository.save(transferencia);
            }
        }
    }

    private void salvarEGravarOutbox(Transferencia transferencia) {
        transferenciaRepository.save(transferencia);
        for (Object evento : transferencia.eventosNaoPublicados()) {
            try {
                String payload = objectMapper.writeValueAsString(evento);
                outboxRepository.save(new OutboxMessage("Transferencia", transferencia.getId(), evento.getClass().getSimpleName(), payload));
            } catch (Exception e) {
                log.error("Erro ao salvar evento no outbox: {}", e.getMessage());
            }
        }
        transferencia.limparEventos();
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void watchdogTimeoutTransfers() {
        LocalDateTime limite = LocalDateTime.now().minusSeconds(30);

        List<Transferencia> travadasEmDebito = transferenciaRepository.findByStatus(StatusTransferencia.CONTA_ORIGEM_DEBITADA);
        for (Transferencia t : travadasEmDebito) {
            if (t.getDataAtualizacao() == null || t.getDataAtualizacao().isBefore(limite)) {
                log.warn("Watchdog detectou transferencia travada em CONTA_ORIGEM_DEBITADA: id={}. Forcando compensacao.", t.getId());
                iniciarCompensacao(t, "Timeout aguardando confirmacao de credito");
            }
        }

        List<Transferencia> travadasEmIniciada = transferenciaRepository.findByStatus(StatusTransferencia.INICIADA);
        for (Transferencia t : travadasEmIniciada) {
            if (t.getDataCriacao() == null || t.getDataCriacao().isBefore(limite)) {
                log.warn("Watchdog detectou transferencia travada em INICIADA: id={}. Forcando compensacao.", t.getId());
                iniciarCompensacao(t, "Timeout aguardando evento ContaDebitada");
            }
        }
    }
}

