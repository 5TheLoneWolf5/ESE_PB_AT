package org.example.transferencia.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.example.transferencia.infrastructure.outbox.OutboxMessage;
import org.example.transferencia.infrastructure.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransferenciaOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransferenciaOutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public TransferenciaOutboxPublisher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publicarEventosPendentes() {
        List<OutboxMessage> pendentes = outboxRepository.findByPublicadoFalseOrderByCriadoEmAsc();
        for (OutboxMessage msg : pendentes) {
            try {
                String payload = msg.getPayload();
                if (msg.getEventType() != null) {
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        if (node.isObject() && (!node.has("eventType") || node.get("eventType").asText().isBlank())) {
                            ((ObjectNode) node).put("eventType", msg.getEventType());
                            payload = objectMapper.writeValueAsString(node);
                        }
                    } catch (Exception ex) {
                        log.warn("Nao foi possivel enriquecer payload outbox: {}", ex.getMessage());
                    }
                }

                kafkaTemplate.send("transferencia-events", String.valueOf(msg.getAggregateId()), payload);
                msg.marcarComoPublicado();
                outboxRepository.save(msg);
                log.info("Evento publicado no Kafka a partir do Outbox: id={}, tipo={}", msg.getId(), msg.getEventType());
            } catch (Exception e) {
                log.warn("Nao foi possivel publicar evento outbox {} no Kafka (tentara novamente): {}", msg.getId(), e.getMessage());
                break;
            }
        }
    }
}

