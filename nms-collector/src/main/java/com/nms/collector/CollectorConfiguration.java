package com.nms.collector;

import com.nms.collector.icmp.IcmpPinger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the collector beans into whichever application imports this module --
 * the server when it polls directly, the proxy when it polls on a customer LAN.
 */
@Configuration
@ComponentScan(basePackageClasses = CollectorConfiguration.class)
public class CollectorConfiguration {

    /**
     * @param cacheTtlSeconds how long one ping run may be reused. A host
     *                        usually has three ICMP items derived from a single
     *                        run, so this must be at least long enough for all
     *                        three to be dispatched, and well below the item
     *                        interval so results stay current.
     */
    @Bean
    public IcmpPinger icmpPinger(@Value("${nms.collector.icmp.cache-ttl-seconds:10}") long cacheTtlSeconds) {
        return new IcmpPinger(Duration.ofSeconds(cacheTtlSeconds));
    }
}
