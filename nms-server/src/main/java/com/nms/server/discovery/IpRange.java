package com.nms.server.discovery;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Expands the address ranges a discovery rule is written in.
 *
 * <p>Four notations, because these are the ones people actually type:
 *
 * <pre>
 *   10.0.0.5                a single address
 *   10.0.0.1-254            a last-octet range
 *   10.0.0.0/24             CIDR
 *   10.0.0.1-254, 10.0.1.0/24, 192.168.5.7      any combination
 * </pre>
 *
 * <p>Addresses are produced lazily. A /16 is 65,536 addresses and a careless
 * /8 is sixteen million; materialising those into a list before scanning
 * starts would exhaust the heap before a single packet was sent, and the
 * failure would look nothing like "that range is too large".
 */
public final class IpRange {

    /**
     * Refused outright rather than scanned. A /8 at even 100 addresses a
     * second takes two days, so accepting it would not be generosity -- it
     * would be a rule that never completes, holding a scan slot forever.
     */
    private static final long MAX_ADDRESSES = 65_536;

    private final List<Block> blocks;
    private final long size;

    private IpRange(List<Block> blocks) {
        this.blocks = blocks;
        long total = 0;
        for (Block block : blocks) {
            total += block.count();
        }
        this.size = total;
    }

    /**
     * Parses a rule's range specification.
     *
     * @throws IllegalArgumentException if the syntax is wrong or the total is
     *                                  larger than {@link #MAX_ADDRESSES}
     */
    public static IpRange parse(String specification) {
        if (specification == null || specification.isBlank()) {
            throw new IllegalArgumentException("No address range given");
        }

        List<Block> blocks = new ArrayList<>();
        for (String part : specification.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                blocks.add(parseBlock(trimmed));
            }
        }

        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("No address range given");
        }

        IpRange range = new IpRange(blocks);
        if (range.size > MAX_ADDRESSES) {
            throw new IllegalArgumentException(
                    "That range covers " + range.size + " addresses; the limit is "
                            + MAX_ADDRESSES + ". Split it into several rules.");
        }
        return range;
    }

    private static Block parseBlock(String text) {
        if (text.contains("/")) {
            return parseCidr(text);
        }
        if (text.contains("-")) {
            return parseHyphenated(text);
        }
        long address = toLong(text);
        return new Block(address, address);
    }

    private static Block parseCidr(String text) {
        String[] halves = text.split("/", 2);
        int prefix;
        try {
            prefix = Integer.parseInt(halves[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' has an invalid prefix length");
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("'" + text + "' has a prefix length outside 0-32");
        }

        long address = toLong(halves[0].trim());
        // The host bits of the given address are discarded, so 10.0.0.7/24
        // means the same network as 10.0.0.0/24. Rejecting it instead would
        // be pedantry: the intent is never ambiguous.
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long network = address & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);

        // For /31 and /32 every address in the block is usable -- a /31 is a
        // point-to-point link and a /32 is one host. Below that, the network
        // and broadcast addresses are skipped: probing them finds nothing and
        // a directed broadcast can provoke replies from every host at once.
        if (prefix >= 31) {
            return new Block(network, broadcast);
        }
        return new Block(network + 1, broadcast - 1);
    }

    private static Block parseHyphenated(String text) {
        int hyphen = text.lastIndexOf('-');
        String left = text.substring(0, hyphen).trim();
        String right = text.substring(hyphen + 1).trim();

        long start = toLong(left);

        long end;
        if (right.contains(".")) {
            // "10.0.0.1-10.0.3.20": a full address on each side.
            end = toLong(right);
        } else {
            // "10.0.0.1-254": only the last octet varies, which is how these
            // are nearly always written.
            int lastOctet;
            try {
                lastOctet = Integer.parseInt(right);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + text + "' has an invalid range end");
            }
            if (lastOctet < 0 || lastOctet > 255) {
                throw new IllegalArgumentException("'" + text + "' ends outside 0-255");
            }
            end = (start & 0xFFFFFF00L) | lastOctet;
        }

        if (end < start) {
            throw new IllegalArgumentException("'" + text + "' ends before it starts");
        }
        return new Block(start, end);
    }

    private static long toLong(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("'" + address + "' is not an IPv4 address");
        }
        long value = 0;
        for (String octet : octets) {
            int number;
            try {
                number = Integer.parseInt(octet.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + address + "' is not an IPv4 address");
            }
            if (number < 0 || number > 255) {
                throw new IllegalArgumentException("'" + address + "' has an octet outside 0-255");
            }
            value = (value << 8) | number;
        }
        return value;
    }

    static String toDotted(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF)
                + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    /** How many addresses this range covers. */
    public long size() {
        return size;
    }

    /** The addresses, in order, generated as they are consumed. */
    public Iterable<String> addresses() {
        return () -> new Iterator<>() {
            private int blockIndex = 0;
            private long current = blocks.isEmpty() ? 0 : blocks.get(0).start();

            @Override
            public boolean hasNext() {
                advanceToNonEmptyBlock();
                return blockIndex < blocks.size();
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return toDotted(current++);
            }

            /**
             * A block can be empty -- a /31 parsed as network+1..broadcast-1
             * would be -- so this skips forward rather than assuming the next
             * block has at least one address in it.
             */
            private void advanceToNonEmptyBlock() {
                while (blockIndex < blocks.size() && current > blocks.get(blockIndex).end()) {
                    blockIndex++;
                    if (blockIndex < blocks.size()) {
                        current = blocks.get(blockIndex).start();
                    }
                }
            }
        };
    }

    /** An inclusive run of addresses, held as 32-bit values. */
    private record Block(long start, long end) {
        long count() {
            return end < start ? 0 : end - start + 1;
        }
    }
}
