package org.example.conta.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.example.conta.infrastructure.persistence.ContaOutboxMessage;
import org.example.conta.infrastructure.persistence.ContaOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContaOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(ContaOutboxPublisher.class);

    private final ContaOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ContaOutboxPublisher(
            ContaOutboxRepository outboxRepository,
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
        List<ContaOutboxMessage> pendentes = outboxRepository.findByPublicadoFalseOrderByCriadoEmAsc();
        for (ContaOutboxMessage msg : pendentes) {
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

                kafkaTemplate.send("conta-events", String.valueOf(msg.getAggregateId()), payload);
                msg.marcarComoPublicado();
                outboxRepository.save(msg);
                log.info("Evento publicado no Kafka a partir do ContaOutbox: id={}, tipo={}", msg.getId(), msg.getEventType());
            } catch (Exception e) {
                log.warn("Nao foi possivel publicar evento outbox {} no Kafka (tentara novamente): {}", msg.getId(), e.getMessage());
                break;
            }
        }
    }
}

