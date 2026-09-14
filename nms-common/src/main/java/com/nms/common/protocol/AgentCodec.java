package com.nms.common.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes framed agent messages.
 *
 * <p>Shared by the agent, the server and the proxy so all three agree on the
 * wire format by construction rather than by convention.
 */
public final class AgentCodec {

    private AgentCodec() {
    }

    /** Writes a framed payload. */
    public static void write(OutputStream out, byte flags, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        ByteBuffer header = ByteBuffer.allocate(AgentProtocol.HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        header.put(AgentProtocol.MAGIC);
        header.put(flags);
        header.putLong(body.length);

        out.write(header.array());
        out.write(body);
        out.flush();
    }

    /**
     * Reads a framed payload.
     *
     * <p>The declared length is checked against {@link AgentProtocol#MAX_PAYLOAD_BYTES}
     * before any buffer is allocated: the length prefix arrives from the
     * network, and a peer that claims a 4 GB payload must be rejected rather
     * than accommodated.
     *
     * @throws IOException when the stream ends early, the magic does not match,
     *                     or the declared length is out of range
     */
    public static Frame read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);

        byte[] header = new byte[AgentProtocol.HEADER_LENGTH];
        try {
            data.readFully(header);
        } catch (EOFException e) {
            throw new IOException("connection closed before a complete header arrived", e);
        }

        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[AgentProtocol.MAGIC.length];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, AgentProtocol.MAGIC)) {
            throw new IOException("not an agent message: bad magic");
        }

        byte flags = buffer.get();
        long length = buffer.getLong();

        if (length < 0 || length > AgentProtocol.MAX_PAYLOAD_BYTES) {
            throw new IOException("declared payload length " + length + " is out of range");
        }

        byte[] body = new byte[(int) length];
        try {
            data.readFully(body);
        } catch (EOFException e) {
            throw new IOException("connection closed after " + length + " bytes were promised", e);
        }

        return new Frame(flags, new String(body, StandardCharsets.UTF_8));
    }

    /** A decoded message: its flags and its payload. */
    public record Frame(byte flags, String payload) {
        public boolean isJson() {
            return (flags & AgentProtocol.FLAG_JSON) != 0;
        }
    }
}
