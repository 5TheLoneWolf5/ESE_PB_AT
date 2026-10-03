package org.example.conta.domain.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

public record DebitoRejeitado(
        Long contaId,
        BigDecimal valor,
        String motivo,
        String chaveIdempotencia,
        Long transferenciaId,
        Instant ocorridoEm
) {
    @JsonProperty("eventType")
    public String eventType() {
        return "DebitoRejeitado";
    }
}

