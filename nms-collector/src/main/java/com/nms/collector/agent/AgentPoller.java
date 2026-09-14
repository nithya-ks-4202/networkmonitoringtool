package com.nms.collector.agent;

import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import com.nms.common.protocol.AgentCodec;
import com.nms.common.protocol.AgentProtocol;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Requests a single item key from a monitoring agent (a "passive" check).
 *
 * <p>The server opens the connection, so the agent needs no outbound access --
 * which is why this mode remains the default inside a data centre. Where the
 * agent sits behind NAT or a one-way firewall, active checks invert the
 * direction instead.
 */
@Component
public class AgentPoller implements Poller {

    @Override
    public CheckType checkType() {
        return CheckType.AGENT_PASSIVE;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        int port = request.intParam("port",
                request.port() > 0 ? request.port() : AgentProtocol.DEFAULT_AGENT_PORT);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(request.address(), port),
                    (int) request.timeout().toMillis());
            // Both halves need the deadline: an agent that accepts the
            // connection and then stalls is as damaging as one that refuses.
            socket.setSoTimeout((int) request.timeout().toMillis());

            OutputStream out = socket.getOutputStream();
            AgentCodec.write(out, AgentProtocol.FLAG_TEXT, request.key());

            InputStream in = socket.getInputStream();
            AgentCodec.Frame frame = AgentCodec.read(in);
            String value = frame.payload().trim();

            if (value.startsWith(AgentProtocol.NOT_SUPPORTED_PREFIX)) {
                // The agent is healthy but does not implement this key: a
                // configuration problem, and it must be visible as one.
                String detail = value.length() > AgentProtocol.NOT_SUPPORTED_PREFIX.length()
                        ? value.substring(AgentProtocol.NOT_SUPPORTED_PREFIX.length()).trim()
                        : "key is not supported by the agent";
                return CheckResult.failed(request.itemId(), detail);
            }

            return convert(request, value);

        } catch (IOException e) {
            return CheckResult.failed(request.itemId(),
                    "agent at " + request.address() + ":" + port + " did not answer: " + e.getMessage());
        }
    }

    private static CheckResult convert(CheckRequest request, String value) {
        ItemValueType valueType = request.valueType();
        if (valueType == null || !valueType.isNumeric()) {
            return CheckResult.ok(request.itemId(), value, valueType == null ? ItemValueType.TEXT : valueType);
        }
        try {
            Object numeric = valueType == ItemValueType.FLOAT
                    ? (Object) Double.parseDouble(value)
                    // Agents legitimately return "1.0" for an integer item;
                    // parsing as a double first avoids rejecting that.
                    : (Object) (long) Double.parseDouble(value);
            return CheckResult.ok(request.itemId(), numeric, valueType);
        } catch (NumberFormatException e) {
            return CheckResult.failed(request.itemId(),
                    "agent returned '" + abbreviate(value) + "' which is not a "
                            + valueType.name().toLowerCase() + " value");
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 60) + "...";
    }
}
