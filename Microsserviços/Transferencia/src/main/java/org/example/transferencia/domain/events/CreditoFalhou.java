package org.example.transferencia.domain.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record CreditoFalhou(
        Long transferenciaId,
        String motivo,
        Instant ocorridoEm
) {
    @JsonProperty("eventType")
    public String eventType() {
        return "CreditoFalhou";
    }
}

