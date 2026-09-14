package com.nms.proxy;

import com.nms.collector.CollectorConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * An on-premise collector.
 *
 * <p>This is what lets a cloud-hosted monitoring platform see a private
 * network. Cameras, switches and servers on a customer LAN have no route from
 * the internet, and no security team will open inbound firewall rules to a
 * monitoring vendor. The proxy sits inside the network, polls locally, and
 * pushes results out over an ordinary outbound HTTPS connection -- the same
 * direction as any other outbound traffic, through the same egress controls.
 *
 * <p>It holds no database. Its configuration is fetched from the server and its
 * results are buffered to local disk until they are acknowledged, so a site
 * whose internet link drops for an hour loses nothing.
 */
@SpringBootApplication
@EnableScheduling
@Import(CollectorConfiguration.class)
public class ProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyApplication.class, args);
    }
}
