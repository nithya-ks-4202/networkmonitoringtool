package com.nms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.common.protocol.AgentProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Answers item keys about the local machine.
 *
 * <p>Keys follow the Zabbix agent vocabulary -- {@code system.cpu.util},
 * {@code vfs.fs.size[/,pused]} -- so existing templates and the muscle memory
 * built around them carry over.
 *
 * <p>Reads go through OSHI, which uses native APIs on Linux, Windows and macOS.
 * One implementation rather than three, and no shelling out to parse the output
 * of tools whose formats differ between distributions.
 */
public class MetricCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricCollector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SystemInfo systemInfo = new SystemInfo();
    private final HardwareAbstractionLayer hardware;
    private final OperatingSystem operatingSystem;

    /**
     * Previous CPU tick counts.
     *
     * <p>CPU utilisation is a rate, not a reading: it can only be derived from
     * the difference between two samples. Holding the previous one here is what
     * makes a single call to {@code system.cpu.util} meaningful.
     */
    private final AtomicReference<long[]> previousCpuTicks = new AtomicReference<>();

    public MetricCollector() {
        this.hardware = systemInfo.getHardware();
        this.operatingSystem = systemInfo.getOperatingSystem();
        // Primed at construction so the first utilisation request has something
        // to compare against rather than returning zero.
        previousCpuTicks.set(hardware.getProcessor().getSystemCpuLoadTicks());
    }

    /**
     * Collects the value for one key.
     *
     * @return the value as text, or a {@code NMS_NOTSUPPORTED} response when the
     *         key is unknown or cannot be read. Returned rather than thrown
     *         because "this agent does not implement that" is a normal answer
     *         that the server records against the item.
     */
    public String collect(String key) {
        try {
            String baseKey = baseKey(key);
            List<String> parameters = parameters(key);

            return switch (baseKey) {
                case "agent.ping" -> "1";
                case "agent.version" -> AgentMain.VERSION;
                case "agent.hostname", "system.hostname" -> operatingSystem.getNetworkParams().getHostName();
                case "system.uptime" -> Long.toString(operatingSystem.getSystemUptime());
                case "system.localtime" -> Long.toString(System.currentTimeMillis() / 1000);
                case "system.cpu.num" -> Integer.toString(hardware.getProcessor().getLogicalProcessorCount());
                case "system.cpu.util" -> cpuUtilisation(parameters);
                case "system.cpu.load" -> loadAverage(parameters);
                case "system.sw.os" -> operatingSystem.toString();
                case "system.users.num" -> Integer.toString(operatingSystem.getSessions().size());
                case "vm.memory.size" -> memory(parameters);
                case "system.swap.size" -> swap(parameters);
                case "vfs.fs.size" -> filesystemSize(parameters);
                case "vfs.fs.inode" -> filesystemInodes(parameters);
                case "vfs.fs.discovery" -> discoverFilesystems();
                case "net.if.discovery" -> discoverInterfaces();
                case "net.if.in" -> networkCounter(parameters, true);
                case "net.if.out" -> networkCounter(parameters, false);
                case "proc.num" -> processCount(parameters);
                default -> notSupported("Unknown item key '" + baseKey + "'");
            };
        } catch (RuntimeException e) {
            // A failure reading one metric must not take the agent down: every
            // other key on this host still needs to be answerable.
            log.warn("Failed to collect '{}': {}", key, e.getMessage());
            return notSupported(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * CPU utilisation since the previous call.
     *
     * <p>{@code system.cpu.util} gives total busy time; a parameter selects a
     * specific state. {@code iowait} and {@code steal} are worth calling out:
     * high iowait with low utilisation means the storage is the bottleneck, not
     * the CPU, and non-zero steal on a virtual machine means the hypervisor is
     * oversubscribed and no amount of tuning inside the guest will help.
     */
    private String cpuUtilisation(List<String> parameters) {
        CentralProcessor processor = hardware.getProcessor();
        long[] previous = previousCpuTicks.get();
        long[] current = processor.getSystemCpuLoadTicks();
        previousCpuTicks.set(current);

        long total = 0;
        for (int i = 0; i < current.length; i++) {
            total += current[i] - previous[i];
        }
        if (total <= 0) {
            // Two samples too close together for any tick to have advanced.
            return "0";
        }

        String state = parameters.size() > 1 ? parameters.get(1).toLowerCase(Locale.ROOT) : "";
        int index = switch (state) {
            case "user" -> CentralProcessor.TickType.USER.getIndex();
            case "nice" -> CentralProcessor.TickType.NICE.getIndex();
            case "system" -> CentralProcessor.TickType.SYSTEM.getIndex();
            case "idle" -> CentralProcessor.TickType.IDLE.getIndex();
            case "iowait" -> CentralProcessor.TickType.IOWAIT.getIndex();
            case "interrupt", "irq" -> CentralProcessor.TickType.IRQ.getIndex();
            case "softirq" -> CentralProcessor.TickType.SOFTIRQ.getIndex();
            case "steal" -> CentralProcessor.TickType.STEAL.getIndex();
            default -> -1;
        };

        if (index < 0) {
            // No state named: total busy time, which is idle's complement.
            long idle = current[CentralProcessor.TickType.IDLE.getIndex()]
                    - previous[CentralProcessor.TickType.IDLE.getIndex()];
            return format(100.0 * (total - idle) / total);
        }

        return format(100.0 * (current[index] - previous[index]) / total);
    }

    private String loadAverage(List<String> parameters) {
        double[] averages = hardware.getProcessor().getSystemLoadAverage(3);
        String window = parameters.size() > 1 ? parameters.get(1).toLowerCase(Locale.ROOT) : "avg1";

        double value = switch (window) {
            case "avg5" -> averages[1];
            case "avg15" -> averages[2];
            default -> averages[0];
        };

        if (value < 0) {
            // Windows has no load average; OSHI signals that with -1.
            return notSupported("Load average is not available on this platform");
        }

        // "per core" normalises by processor count, so the same threshold means
        // the same thing on a 2-core and a 64-core machine.
        boolean perCore = !parameters.isEmpty() && "percpu".equalsIgnoreCase(parameters.get(0));
        return format(perCore ? value / hardware.getProcessor().getLogicalProcessorCount() : value);
    }

    private String memory(List<String> parameters) {
        GlobalMemory memory = hardware.getMemory();
        long total = memory.getTotal();
        // Available, not free: reclaimable page cache is not memory pressure,
        // and alerting on free memory means alerting on every healthy machine.
        long available = memory.getAvailable();
        long used = total - available;

        String mode = parameters.isEmpty() ? "total" : parameters.get(0).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "total" -> Long.toString(total);
            case "free", "available" -> Long.toString(available);
            case "used" -> Long.toString(used);
            case "pavailable", "pfree" -> format(total == 0 ? 0 : 100.0 * available / total);
            case "pused" -> format(total == 0 ? 0 : 100.0 * used / total);
            default -> notSupported("Unknown memory mode '" + mode + "'");
        };
    }

    private String swap(List<String> parameters) {
        GlobalMemory memory = hardware.getMemory();
        long total = memory.getVirtualMemory().getSwapTotal();
        long used = memory.getVirtualMemory().getSwapUsed();
        long free = total - used;

        String mode = parameters.size() > 1 ? parameters.get(1).toLowerCase(Locale.ROOT) : "total";
        return switch (mode) {
            case "total" -> Long.toString(total);
            case "free" -> Long.toString(free);
            case "used" -> Long.toString(used);
            // Guarded against division by zero: a host configured without swap
            // is normal, and it must not make the item permanently unsupported.
            case "pfree" -> format(total == 0 ? 100 : 100.0 * free / total);
            case "pused" -> format(total == 0 ? 0 : 100.0 * used / total);
            default -> notSupported("Unknown swap mode '" + mode + "'");
        };
    }

    private String filesystemSize(List<String> parameters) {
        if (parameters.isEmpty()) {
            return notSupported("vfs.fs.size needs a mount point, e.g. vfs.fs.size[/,pused]");
        }
        OSFileStore store = findFileStore(parameters.get(0));
        if (store == null) {
            return notSupported("No mounted filesystem at '" + parameters.get(0) + "'");
        }

        long total = store.getTotalSpace();
        long free = store.getUsableSpace();
        long used = total - free;

        String mode = parameters.size() > 1 ? parameters.get(1).toLowerCase(Locale.ROOT) : "total";
        return switch (mode) {
            case "total" -> Long.toString(total);
            case "free" -> Long.toString(free);
            case "used" -> Long.toString(used);
            case "pfree" -> format(total == 0 ? 0 : 100.0 * free / total);
            case "pused" -> format(total == 0 ? 0 : 100.0 * used / total);
            default -> notSupported("Unknown filesystem mode '" + mode + "'");
        };
    }

    /**
     * Inode usage.
     *
     * <p>Worth monitoring separately from space: a filesystem holding millions
     * of tiny files can exhaust its inodes with most of its capacity free, and
     * writes then fail with an error that looks nothing like "disk full".
     */
    private String filesystemInodes(List<String> parameters) {
        if (parameters.isEmpty()) {
            return notSupported("vfs.fs.inode needs a mount point");
        }
        OSFileStore store = findFileStore(parameters.get(0));
        if (store == null) {
            return notSupported("No mounted filesystem at '" + parameters.get(0) + "'");
        }

        long total = store.getTotalInodes();
        long free = store.getFreeInodes();
        if (total <= 0) {
            return notSupported("This filesystem does not report inode counts");
        }

        String mode = parameters.size() > 1 ? parameters.get(1).toLowerCase(Locale.ROOT) : "total";
        return switch (mode) {
            case "total" -> Long.toString(total);
            case "free" -> Long.toString(free);
            case "used" -> Long.toString(total - free);
            case "pfree" -> format(100.0 * free / total);
            case "pused" -> format(100.0 * (total - free) / total);
            default -> notSupported("Unknown inode mode '" + mode + "'");
        };
    }

    /** Mounted filesystems, as low-level discovery entities. */
    private String discoverFilesystems() {
        ArrayNode entities = JSON.createArrayNode();
        for (OSFileStore store : operatingSystem.getFileSystem().getFileStores()) {
            if (store.getTotalSpace() <= 0) {
                // Pseudo-filesystems: no capacity to run out of, so monitoring
                // them would create items that can never say anything useful.
                continue;
            }
            ObjectNode entity = entities.addObject();
            entity.put("{#FSNAME}", store.getMount());
            entity.put("{#FSTYPE}", store.getType());
            entity.put("{#FSLABEL}", store.getLabel());
        }
        return entities.toString();
    }

    /** Network interfaces, as low-level discovery entities. */
    private String discoverInterfaces() {
        ArrayNode entities = JSON.createArrayNode();
        for (NetworkIF networkInterface : hardware.getNetworkIFs()) {
            if (networkInterface.getIfOperStatus() == NetworkIF.IfOperStatus.DOWN
                    && networkInterface.getBytesRecv() == 0) {
                // Never used and currently down: almost always an unconnected
                // port, and discovering it creates items that stay flat forever.
                continue;
            }
            ObjectNode entity = entities.addObject();
            entity.put("{#IFNAME}", networkInterface.getName());
            entity.put("{#IFALIAS}", networkInterface.getDisplayName());
            entity.put("{#IFTYPE}", networkInterface.getIfType());
        }
        return entities.toString();
    }

    /**
     * A network interface's byte counter.
     *
     * <p>The raw cumulative counter is returned, not a rate. Converting it to
     * throughput is the server's change-per-second preprocessing step, which
     * also handles the counter wrapping that would otherwise produce an
     * enormous spike once per rollover.
     */
    private String networkCounter(List<String> parameters, boolean inbound) {
        if (parameters.isEmpty()) {
            return notSupported("net.if.in/out needs an interface name");
        }
        String name = parameters.get(0);

        for (NetworkIF networkInterface : hardware.getNetworkIFs()) {
            if (networkInterface.getName().equals(name)
                    || networkInterface.getDisplayName().equals(name)) {
                networkInterface.updateAttributes();
                return Long.toString(inbound
                        ? networkInterface.getBytesRecv() : networkInterface.getBytesSent());
            }
        }
        return notSupported("No network interface named '" + name + "'");
    }

    private String processCount(List<String> parameters) {
        List<OSProcess> processes = operatingSystem.getProcesses();

        String name = parameters.isEmpty() ? "" : parameters.get(0);
        String state = parameters.size() > 2 ? parameters.get(2).toLowerCase(Locale.ROOT) : "";

        long count = processes.stream()
                .filter(process -> name.isEmpty() || process.getName().equals(name))
                .filter(process -> state.isEmpty() || matchesState(process, state))
                .count();

        return Long.toString(count);
    }

    private static boolean matchesState(OSProcess process, String state) {
        return switch (state) {
            case "run", "running" -> process.getState() == OSProcess.State.RUNNING;
            case "sleep", "sleeping" -> process.getState() == OSProcess.State.SLEEPING;
            case "zomb", "zombie" -> process.getState() == OSProcess.State.ZOMBIE;
            case "stop", "stopped" -> process.getState() == OSProcess.State.STOPPED;
            default -> true;
        };
    }

    private OSFileStore findFileStore(String mount) {
        for (OSFileStore store : operatingSystem.getFileSystem().getFileStores()) {
            if (store.getMount().equals(mount)) {
                return store;
            }
        }
        return null;
    }

    /** Extracts {@code vfs.fs.size} from {@code vfs.fs.size[/var,pused]}. */
    static String baseKey(String key) {
        int bracket = key.indexOf('[');
        return (bracket < 0 ? key : key.substring(0, bracket)).trim();
    }

    /**
     * Extracts the bracketed parameters.
     *
     * <p>Quoted parameters are unwrapped, so a mount point containing a comma
     * can be written {@code vfs.fs.size["/mnt/a,b",pused]} and survive the split.
     */
    static List<String> parameters(String key) {
        int open = key.indexOf('[');
        if (open < 0) {
            return List.of();
        }
        int close = key.lastIndexOf(']');
        String inner = key.substring(open + 1, close < 0 ? key.length() : close);

        List<String> parameters = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (char character : inner.toCharArray()) {
            if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                parameters.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        parameters.add(current.toString().trim());
        return parameters;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String notSupported(String reason) {
        return AgentProtocol.NOT_SUPPORTED_PREFIX + " " + reason;
    }
}
