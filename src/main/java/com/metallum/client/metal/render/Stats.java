package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Stats {
    private static final AtomicLong CREATED_BUFFERS = new AtomicLong();

    private static final ConcurrentHashMap<Integer, UsageStats> USAGE_STATS = new ConcurrentHashMap<>();

    private static final class UsageStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong requestedBytes = new AtomicLong();
        final AtomicLong allocatedBytes = new AtomicLong();
    }

    public static void recordUsage(int usage, long requestedSize, long allocatedSize) {
        UsageStats stats = USAGE_STATS.computeIfAbsent(usage, k -> new UsageStats());

        stats.count.incrementAndGet();
        stats.requestedBytes.addAndGet(requestedSize);
        stats.allocatedBytes.addAndGet(allocatedSize);

        long total = CREATED_BUFFERS.incrementAndGet();

        if (total % 100 == 0) {
            StringBuilder sb = new StringBuilder(1024);

            sb.append("MetalGpuBuffer stats after ")
                    .append(total)
                    .append(" allocations:\n");

            long totalRequested = 0;
            long totalAllocated = 0;

            for (Map.Entry<Integer, UsageStats> entry : USAGE_STATS.entrySet()) {
                UsageStats s = entry.getValue();

                long requested = s.requestedBytes.get();
                long allocated = s.allocatedBytes.get();
                long overhead = allocated - requested;

                totalRequested += requested;
                totalAllocated += allocated;

                sb.append("  ")
                        .append(describeUsage(entry.getKey()))
                        .append('\n')
                        .append("    count      = ").append(s.count.get()).append('\n')
                        .append("    requested  = ").append(formatBytes(requested)).append('\n')
                        .append("    allocated  = ").append(formatBytes(allocated)).append('\n')
                        .append("    overhead   = ").append(formatBytes(overhead)).append('\n');
            }

            sb.append("TOTAL\n")
                    .append("  requested = ").append(formatBytes(totalRequested)).append('\n')
                    .append("  allocated = ").append(formatBytes(totalAllocated)).append('\n')
                    .append("  overhead  = ").append(formatBytes(totalAllocated - totalRequested));

            Metallum.LOGGER.info(sb.toString());
        }
    }

    public static void writeUsage(int usage, long allocatedSize) {
        UsageStats stats = USAGE_STATS.computeIfAbsent(usage, k -> new UsageStats());

        String sb = "MetalGpuBuffer stats" +
                "  " +
                describeUsage(usage) +
                "  requested = " + formatBytes(allocatedSize);


        Metallum.LOGGER.info(sb);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int z = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format("%.2f %sB",
                (double) bytes / (1L << (z * 10)),
                " KMGTPE".charAt(z));
    }

    private static String describeUsage(int usage) {
        List<String> flags = new ArrayList<>();

        if ((usage & GpuBuffer.USAGE_MAP_READ) != 0) flags.add("MAP_READ");
        if ((usage & GpuBuffer.USAGE_MAP_WRITE) != 0) flags.add("MAP_WRITE");
        if ((usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0) flags.add("CLIENT_STORAGE");
        if ((usage & GpuBuffer.USAGE_COPY_DST) != 0) flags.add("COPY_DST");
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) flags.add("COPY_SRC");
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) flags.add("VERTEX");
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) flags.add("INDEX");
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) flags.add("UNIFORM");
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) flags.add("UNIFORM_TEXEL");
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) flags.add("INDIRECT");

        return flags.isEmpty()
                ? "NONE(" + usage + ")"
                : String.join("|", flags);
    }
}
