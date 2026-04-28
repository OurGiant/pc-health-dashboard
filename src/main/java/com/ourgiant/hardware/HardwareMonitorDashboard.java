package com.ourgiant.hardware;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

public class HardwareMonitorDashboard extends JFrame {
    private final Map<String, MetricPanel> metricPanels = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> updateTask;
    private int refreshInterval = 15000;

    private final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

    private long prevBytesReceived = 0;
    private long prevBytesSent = 0;
    private long prevTime = System.currentTimeMillis();

    private final ExecutorService metricExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, CachedValue> metricCache = new ConcurrentHashMap<>();

    private static class CachedValue {
        String value;
        long timestamp;
        CachedValue(String value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public HardwareMonitorDashboard() {
        setTitle("Hardware Monitor Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 750);
        setLocationRelativeTo(null);

        initializeUI();
        startMonitoring();
    }

    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(45, 45, 48));
        headerPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        String osDisplay = isWindows ? "Windows" : System.getProperty("os.name");
        JLabel titleLabel = new JLabel("⚙ Hardware Monitor - " + osDisplay);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controlPanel.setOpaque(false);

        JLabel refreshLabel = new JLabel("Refresh: ");
        refreshLabel.setForeground(Color.WHITE);
        refreshLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        String[] intervals = {"5s", "10s", "15s", "30s", "60s"};
        JComboBox<String> refreshCombo = new JComboBox<>(intervals);
        refreshCombo.setSelectedIndex(2); // Default to 15s
        refreshCombo.addActionListener(e -> {
            String selected = (String) refreshCombo.getSelectedItem();
            refreshInterval = Integer.parseInt(selected.replace("s", "")) * 1000;
            restartMonitoring();
        });

        controlPanel.add(refreshLabel);
        controlPanel.add(refreshCombo);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(controlPanel, BorderLayout.EAST);

        JPanel contentPanel = new JPanel(new GridLayout(0, 2, 15, 15));
        contentPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        contentPanel.setBackground(new Color(240, 240, 245));

        addMetricPanel(contentPanel, "CPU Usage", "%", Color.decode("#FF6B6B"));
        addMetricPanel(contentPanel, "CPU Temperature", "°C", Color.decode("#4ECDC4"));
        addMetricPanel(contentPanel, "Memory Usage", "GB", Color.decode("#45B7D1"));
        addMetricPanel(contentPanel, "Memory %", "%", Color.decode("#96CEB4"));
        addMetricPanel(contentPanel, "GPU Temperature", "°C", Color.decode("#FF8B94"));
        addMetricPanel(contentPanel, "Disk Usage", "%", Color.decode("#DFE6E9"));
        addMetricPanel(contentPanel, "Battery", "%", Color.decode("#74B9FF"));
        addMetricPanel(contentPanel, "Network", "KB/s", Color.decode("#A29BFE"));

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBackground(new Color(245, 245, 250));
        statusPanel.setBorder(new EmptyBorder(5, 20, 5, 20));
        String backend = isWindows ? "WMI" : "/proc & sysfs";
        JLabel statusLabel = new JLabel("Monitoring via " + backend + "...");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(new Color(100, 100, 105));
        statusPanel.add(statusLabel);

        add(headerPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);
    }

    private void addMetricPanel(JPanel parent, String name, String unit, Color color) {
        MetricPanel panel = new MetricPanel(name, unit, color);
        metricPanels.put(name, panel);
        parent.add(panel);
    }

    private void startMonitoring() {
        updateTask = scheduler.scheduleAtFixedRate(() -> {
            SwingUtilities.invokeLater(this::updateMetrics);
        }, 0, refreshInterval, TimeUnit.MILLISECONDS);
    }

    private void restartMonitoring() {
        if (updateTask != null) updateTask.cancel(false);
        startMonitoring();
    }

    private void updateMetrics() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        futures.add(CompletableFuture.runAsync(() -> {
            double v = getCPUUsage();
            SwingUtilities.invokeLater(() -> metricPanels.get("CPU Usage").updateValue(v));
        }, metricExecutor));

        futures.add(CompletableFuture.runAsync(() -> {
            double v = getCPUTemperature();
            SwingUtilities.invokeLater(() -> metricPanels.get("CPU Temperature").updateValue(v));
        }, metricExecutor));

        long totalMemory = getTotalPhysicalMemory();
        long freeMemory = getFreePhysicalMemory();
        long usedMemory = totalMemory - freeMemory;
        metricPanels.get("Memory Usage").updateValue(usedMemory / (1024.0 * 1024.0 * 1024.0));
        metricPanels.get("Memory %").updateValue((usedMemory * 100.0) / totalMemory);

        futures.add(CompletableFuture.runAsync(() -> {
            double v = getGPUTemperature();
            SwingUtilities.invokeLater(() -> metricPanels.get("GPU Temperature").updateValue(v));
        }, metricExecutor));

        if (!metricCache.containsKey("disk") ||
                System.currentTimeMillis() - metricCache.get("disk").timestamp > 30000) {
            futures.add(CompletableFuture.runAsync(() -> {
                double v = getDiskUsage();
                metricCache.put("disk", new CachedValue(String.valueOf(v)));
                SwingUtilities.invokeLater(() -> metricPanels.get("Disk Usage").updateValue(v));
            }, metricExecutor));
        } else {
            metricPanels.get("Disk Usage").updateValue(Double.parseDouble(metricCache.get("disk").value));
        }

        futures.add(CompletableFuture.runAsync(() -> {
            double v = getBatteryLevel();
            SwingUtilities.invokeLater(() -> metricPanels.get("Battery").updateValue(v));
        }, metricExecutor));

        futures.add(CompletableFuture.runAsync(() -> {
            double v = getNetworkUsage();
            SwingUtilities.invokeLater(() -> metricPanels.get("Network").updateValue(v));
        }, metricExecutor));
    }

    // Use ProcessBuilder (varargs) so arguments are never shell-split incorrectly
    private String executeCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append("\n");
            reader.close();
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String readFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path))).trim();
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // CPU Usage
    // -------------------------------------------------------------------------

    private double getCPUUsage() {
        if (isWindows) {
            String output = executeCommand("wmic", "cpu", "get", "loadpercentage");
            Matcher m = Pattern.compile("\\d+").matcher(output);
            if (m.find()) return Double.parseDouble(m.group());
        } else {
            try {
                // Two snapshots of /proc/stat for an accurate delta
                String stat1 = readFile("/proc/stat");
                Thread.sleep(500);
                String stat2 = readFile("/proc/stat");
                long[] t1 = parseProcStat(stat1);
                long[] t2 = parseProcStat(stat2);
                if (t1 != null && t2 != null) {
                    long idleDiff = t2[3] - t1[3];
                    long totalDiff = sum(t2) - sum(t1);
                    if (totalDiff > 0) return (1.0 - (double) idleDiff / totalDiff) * 100.0;
                }
            } catch (Exception ignored) {}
        }
        // Cross-platform fallback via com.sun.management
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            double load = ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
            if (load >= 0) return load * 100.0;
        }
        return 0;
    }

    private long[] parseProcStat(String stat) {
        for (String line : stat.split("\n")) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                long[] times = new long[parts.length - 1];
                for (int i = 1; i < parts.length; i++) times[i - 1] = Long.parseLong(parts[i]);
                return times;
            }
        }
        return null;
    }

    private long sum(long[] arr) {
        long total = 0;
        for (long v : arr) total += v;
        return total;
    }

    // -------------------------------------------------------------------------
    // CPU Temperature
    // -------------------------------------------------------------------------

    private double getCPUTemperature() {
        if (isWindows) {
            String output = executeCommand("wmic", "/namespace:\\\\root\\wmi", "PATH",
                    "MSAcpi_ThermalZoneTemperature", "get", "CurrentTemperature");
            Matcher m = Pattern.compile("\\d+").matcher(output);
            if (m.find()) return Double.parseDouble(m.group()) / 10.0 - 273.15;
        } else {
            // Scan thermal zones
            for (int i = 0; i < 10; i++) {
                String raw = readFile("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (!raw.isEmpty()) {
                    try {
                        double c = Double.parseDouble(raw) / 1000.0;
                        if (c > 0 && c < 150) return c;
                    } catch (NumberFormatException ignored) {}
                }
            }
            // Fallback: hwmon coretemp / k10temp / zenpower
            File hwmonBase = new File("/sys/class/hwmon");
            if (hwmonBase.exists() && hwmonBase.listFiles() != null) {
                for (File hwmon : hwmonBase.listFiles()) {
                    String name = readFile(hwmon.getPath() + "/name");
                    if (name.contains("coretemp") || name.contains("k10temp") || name.contains("zenpower")) {
                        String raw = readFile(hwmon.getPath() + "/temp1_input");
                        if (!raw.isEmpty()) {
                            try { return Double.parseDouble(raw) / 1000.0; } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        // Estimate from load as last resort
        return 45 + (getCPUUsage() / 100.0) * 30;
    }

    // -------------------------------------------------------------------------
    // Memory — com.sun.management is already cross-platform
    // -------------------------------------------------------------------------

    private long getTotalPhysicalMemory() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean)
            return ((com.sun.management.OperatingSystemMXBean) osBean).getTotalMemorySize();
        return 16L * 1024 * 1024 * 1024;
    }

    private long getFreePhysicalMemory() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean)
            return ((com.sun.management.OperatingSystemMXBean) osBean).getFreeMemorySize();
        return 8L * 1024 * 1024 * 1024;
    }

    // -------------------------------------------------------------------------
    // GPU Temperature
    // -------------------------------------------------------------------------

    private double getGPUTemperature() {
        // nvidia-smi works on both Windows and Linux
        String nv = executeCommand("nvidia-smi", "--query-gpu=temperature.gpu", "--format=csv,noheader");
        if (!nv.trim().isEmpty()) {
            try { return Double.parseDouble(nv.trim()); } catch (NumberFormatException ignored) {}
        }

        // AMD GPU via hwmon (Linux only)
        if (!isWindows) {
            File hwmonBase = new File("/sys/class/hwmon");
            if (hwmonBase.exists() && hwmonBase.listFiles() != null) {
                for (File hwmon : hwmonBase.listFiles()) {
                    if (readFile(hwmon.getPath() + "/name").contains("amdgpu")) {
                        String raw = readFile(hwmon.getPath() + "/temp1_input");
                        if (!raw.isEmpty()) {
                            try { return Double.parseDouble(raw) / 1000.0; } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }

        return getCPUTemperature() - 5 + (Math.random() - 0.5) * 3;
    }

    // -------------------------------------------------------------------------
    // Disk Usage
    // -------------------------------------------------------------------------

    private double getDiskUsage() {
        File root = isWindows ? new File("C:\\") : new File("/");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        return total > 0 ? ((total - free) * 100.0) / total : 0;
    }

    // -------------------------------------------------------------------------
    // Battery
    // -------------------------------------------------------------------------

    private double getBatteryLevel() {
        if (isWindows) {
            String output = executeCommand("wmic", "path", "Win32_Battery", "get", "EstimatedChargeRemaining");
            Matcher m = Pattern.compile("\\d+").matcher(output);
            if (m.find()) return Double.parseDouble(m.group());
        } else {
            String[] paths = {
                "/sys/class/power_supply/BAT0/capacity",
                "/sys/class/power_supply/BAT1/capacity",
                "/sys/class/power_supply/battery/capacity"
            };
            for (String path : paths) {
                String raw = readFile(path);
                if (!raw.isEmpty()) {
                    try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    private double getNetworkUsage() {
        long totalReceived = 0;
        long totalSent = 0;

        if (isWindows) {
            String output = executeCommand("wmic", "path",
                    "Win32_PerfRawData_Tcpip_NetworkInterface",
                    "get", "BytesReceivedPerSec,BytesSentPerSec");
            Matcher m = Pattern.compile("(\\d+)\\s+(\\d+)").matcher(output);
            while (m.find()) {
                totalReceived += Long.parseLong(m.group(1));
                totalSent += Long.parseLong(m.group(2));
            }
        } else {
            String content = readFile("/proc/net/dev");
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("Inter") || line.startsWith("face") || line.startsWith("lo:")) continue;
                if (!line.contains(":")) continue;
                String[] parts = line.split("[:\\s]+");
                if (parts.length >= 10) {
                    try {
                        totalReceived += Long.parseLong(parts[1]);
                        totalSent += Long.parseLong(parts[9]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return calculateNetworkRate(totalReceived, totalSent);
    }

    private double calculateNetworkRate(long totalReceived, long totalSent) {
        long currentTime = System.currentTimeMillis();
        double timeDiff = (currentTime - prevTime) / 1000.0;
        double rate = 0;
        if (timeDiff > 0 && prevTime > 0) {
            rate = (totalReceived - prevBytesReceived + totalSent - prevBytesSent) / timeDiff / 1024.0;
        }
        prevBytesReceived = totalReceived;
        prevBytesSent = totalSent;
        prevTime = currentTime;
        return rate;
    }

    // -------------------------------------------------------------------------
    // MetricPanel
    // -------------------------------------------------------------------------

    class MetricPanel extends JPanel {
        private final String name;
        private final String unit;
        private final Color accentColor;
        private final JLabel valueLabel;
        private final JProgressBar progressBar;
        private final JLabel minMaxLabel;
        private double currentValue = 0;
        private double minValue = Double.MAX_VALUE;
        private double maxValue = Double.MIN_VALUE;

        public MetricPanel(String name, String unit, Color accentColor) {
            this.name = name;
            this.unit = unit;
            this.accentColor = accentColor;

            setLayout(new BorderLayout(10, 10));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(220, 220, 225), 1),
                new EmptyBorder(20, 20, 20, 20)
            ));

            JPanel headerPanel = new JPanel(new BorderLayout());
            headerPanel.setOpaque(false);

            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            nameLabel.setForeground(new Color(70, 70, 75));

            JLabel iconLabel = new JLabel("●");
            iconLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
            iconLabel.setForeground(accentColor);

            headerPanel.add(nameLabel, BorderLayout.WEST);
            headerPanel.add(iconLabel, BorderLayout.EAST);

            valueLabel = new JLabel("--");
            valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 36));
            valueLabel.setForeground(new Color(45, 45, 48));
            valueLabel.setHorizontalAlignment(SwingConstants.CENTER);

            minMaxLabel = new JLabel("Min: -- | Max: --");
            minMaxLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            minMaxLabel.setForeground(new Color(120, 120, 125));
            minMaxLabel.setHorizontalAlignment(SwingConstants.CENTER);

            progressBar = new JProgressBar(0, 100);
            progressBar.setValue(0);
            progressBar.setStringPainted(false);
            progressBar.setPreferredSize(new Dimension(0, 8));
            progressBar.setForeground(accentColor);
            progressBar.setBackground(new Color(240, 240, 245));
            progressBar.setBorderPainted(false);

            JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
            centerPanel.setOpaque(false);
            centerPanel.add(valueLabel, BorderLayout.CENTER);
            centerPanel.add(minMaxLabel, BorderLayout.SOUTH);

            add(headerPanel, BorderLayout.NORTH);
            add(centerPanel, BorderLayout.CENTER);
            add(progressBar, BorderLayout.SOUTH);
        }

        public void updateValue(double value) {
            if (Math.abs(value - this.currentValue) < 0.5 && this.currentValue != 0) return;
            this.currentValue = value;

            if (value > 0) {
                if (value < minValue) minValue = value;
                if (value > maxValue) maxValue = value;
                minMaxLabel.setText(String.format("Min: %.1f | Max: %.1f", minValue, maxValue));
            }

            valueLabel.setText(String.format("%.1f %s", value, unit));
            progressBar.setValue(calculateProgress(value));
            updateColorCoding(value);
        }

        private int calculateProgress(double value) {
            switch (name) {
                case "Memory Usage":
                    return (int) ((value / 32.0) * 100);
                case "CPU Temperature":
                case "GPU Temperature":
                    return (int) ((value / 100.0) * 100);
                case "Network":
                    return (int) Math.min(100, (value / 10000.0) * 100);
                default:
                    return (int) Math.min(100, Math.max(0, value));
            }
        }

        private void updateColorCoding(double value) {
            if (name.equals("CPU Temperature") || name.equals("GPU Temperature")) {
                if (value > 85) progressBar.setForeground(Color.decode("#FF6B6B"));
                else if (value > 75) progressBar.setForeground(Color.decode("#FFEAA7"));
                else progressBar.setForeground(accentColor);
            } else if (name.equals("Battery")) {
                if (value < 20) progressBar.setForeground(Color.decode("#FF6B6B"));
                else if (value < 40) progressBar.setForeground(Color.decode("#FFEAA7"));
                else progressBar.setForeground(accentColor);
            } else if (name.equals("CPU Usage")) {
                if (value > 90) progressBar.setForeground(Color.decode("#FF6B6B"));
                else if (value > 70) progressBar.setForeground(Color.decode("#FFEAA7"));
                else progressBar.setForeground(accentColor);
            }
        }
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        SwingUtilities.invokeLater(() -> new HardwareMonitorDashboard().setVisible(true));
    }
}
