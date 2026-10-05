package com.schmebb.hub;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param participants   bank code → base URL of that bank's API
 * @param schemeLimitCents largest single payment allowed
 * @param forwardTimeout how long to wait for the payee bank's answer
 * @param timeZone       defines "today" for the settlement report
 */
@ConfigurationProperties("hub")
public record HubProperties(
        Map<String, URI> participants,
        long schemeLimitCents,
        Duration forwardTimeout,
        ZoneId timeZone) {
}
