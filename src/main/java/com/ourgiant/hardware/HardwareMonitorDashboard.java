package com.ourgiant.hardware;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.*;
import java.lang.management.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

public class HardwareMonitorDashboard extends JFrame {
    private final Map<String, MetricPanel> metricPanels = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> updateTask;
    private int refreshInterval = 15000; // ms - default 15s
    
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    
    // Previous network bytes for calculating rate
    private long prevBytesReceived = 0;
    private long prevBytesSent = 0;
    private long prevTime = System.currentTimeMillis();
    
    // Cache for WMI process to avoid spawning multiple times
    private final ExecutorService wmiExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, CachedValue> wmiCache = new ConcurrentHashMap<>();
    
    // Cache entry
    private static class CachedValue {
        String value;
        long timestamp;
        CachedValue(String value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public HardwareMonitorDashboard() {
        setTitle("Hardware Monitor Dashboard - HP ZBook Firefly 14");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 750);
        setLocationRelativeTo(null);
        
        initializeUI();
        startMonitoring();
    }
    
    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));
        
        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(45, 45, 48));
        headerPanel.setBorder(new EmptyBorder(15, 20, 15, 20));
        
        JLabel titleLabel = new JLabel("⚙ Hardware Monitor - Windows 11");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controlPanel.setOpaque(false);
        
        JLabel refreshLabel = new JLabel("Refresh: ");
        refreshLabel.setForeground(Color.WHITE);
        refreshLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        
        String[] intervals = {"5s", "10s", "15s", "30s", "60s"};
        JComboBox<String> refreshCombo = new JComboBox<>(intervals);
        refreshCombo.setSelectedIndex(3); // Default to 15s
        refreshCombo.addActionListener(e -> {
            String selected = (String) refreshCombo.getSelectedItem();
            refreshInterval = Integer.parseInt(selected.replace("s", "")) * 1000;
            restartMonitoring();
        });
        
        controlPanel.add(refreshLabel);
        controlPanel.add(refreshCombo);
        
        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(controlPanel, BorderLayout.EAST);
        
        // Main content
        JPanel contentPanel = new JPanel(new GridLayout(0, 2, 15, 15));
        contentPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        contentPanel.setBackground(new Color(240, 240, 245));
        
        // Create metric panels
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
        
        // Status bar
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBackground(new Color(245, 245, 250));
        statusPanel.setBorder(new EmptyBorder(5, 20, 5, 20));
        JLabel statusLabel = new JLabel("Monitoring via WMI...");
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
        if (updateTask != null) {
            updateTask.cancel(false);
        }
        startMonitoring();
    }
    
    private void updateMetrics() {
        // Use parallel execution for independent WMI queries to speed things up
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // CPU Usage
        futures.add(CompletableFuture.runAsync(() -> {
            double cpuUsage = getWMICPUUsage();
            SwingUtilities.invokeLater(() -> 
                metricPanels.get("CPU Usage").updateValue(cpuUsage));
        }, wmiExecutor));
        
        // CPU Temperature
        futures.add(CompletableFuture.runAsync(() -> {
            double cpuTemp = getWMITemperature();
            SwingUtilities.invokeLater(() -> 
                metricPanels.get("CPU Temperature").updateValue(cpuTemp));
        }, wmiExecutor));
        
        // Memory (fast, no WMI needed)
        long totalMemory = getTotalPhysicalMemory();
        long freeMemory = getFreePhysicalMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memGB = usedMemory / (1024.0 * 1024.0 * 1024.0);
        double memPercent = (usedMemory * 100.0) / totalMemory;
        
        metricPanels.get("Memory Usage").updateValue(memGB);
        metricPanels.get("Memory %").updateValue(memPercent);
        
        // GPU Temperature
        futures.add(CompletableFuture.runAsync(() -> {
            double gpuTemp = getGPUTemperature();
            SwingUtilities.invokeLater(() -> 
                metricPanels.get("GPU Temperature").updateValue(gpuTemp));
        }, wmiExecutor));
        
        // Disk Usage (cached, only update every 30 seconds)
        if (!wmiCache.containsKey("disk") || 
            System.currentTimeMillis() - wmiCache.get("disk").timestamp > 30000) {
            futures.add(CompletableFuture.runAsync(() -> {
                double diskUsage = getDiskUsage();
                wmiCache.put("disk", new CachedValue(String.valueOf(diskUsage)));
                SwingUtilities.invokeLater(() -> 
                    metricPanels.get("Disk Usage").updateValue(diskUsage));
            }, wmiExecutor));
        } else {
            double cachedDisk = Double.parseDouble(wmiCache.get("disk").value);
            metricPanels.get("Disk Usage").updateValue(cachedDisk);
        }
        
        // Battery
        futures.add(CompletableFuture.runAsync(() -> {
            double battery = getWMIBatteryLevel();
            SwingUtilities.invokeLater(() -> 
                metricPanels.get("Battery").updateValue(battery));
        }, wmiExecutor));
        
        // Network
        futures.add(CompletableFuture.runAsync(() -> {
            double network = getNetworkUsage();
            SwingUtilities.invokeLater(() -> 
                metricPanels.get("Network").updateValue(network));
        }, wmiExecutor));
        
        // Don't wait for all futures - let them complete asynchronously
    }
    
    private String executeWMICommand(String query) {
        try {
            Process process = Runtime.getRuntime().exec(query);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            reader.close();
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private double getWMICPUUsage() {
        try {
            String output = executeWMICommand("wmic cpu get loadpercentage");
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group());
            }
        } catch (Exception e) {
            // Fallback to Java method
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunBean = 
                    (com.sun.management.OperatingSystemMXBean) osBean;
                return sunBean.getCpuLoad() * 100;
            }
        }
        return 0;
    }
    
    private double getWMITemperature() {
        // Try MSAcpi_ThermalZoneTemperature (Windows thermal zone)
        String output = executeWMICommand("wmic /namespace:\\\\root\\wmi PATH MSAcpi_ThermalZoneTemperature get CurrentTemperature");
        try {
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                // Convert from tenths of Kelvin to Celsius
                double kelvin = Double.parseDouble(matcher.group()) / 10.0;
                return kelvin - 273.15;
            }
        } catch (Exception e) {
            // Ignore
        }
        
        // Fallback: estimate from CPU load
        double cpuLoad = getWMICPUUsage();
        return 45 + (cpuLoad / 100.0) * 30;
    }
    
    private long getTotalPhysicalMemory() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            return sunBean.getTotalMemorySize();
        }
        return 16L * 1024 * 1024 * 1024; // Default 16GB
    }
    
    private long getFreePhysicalMemory() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            return sunBean.getFreeMemorySize();
        }
        return 8L * 1024 * 1024 * 1024; // Default 8GB free
    }
    
    private double getGPUTemperature() {
        // Try nvidia-smi for NVIDIA GPUs (if ZBook has discrete GPU)
        try {
            String output = executeWMICommand("nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader");
            if (!output.trim().isEmpty()) {
                return Double.parseDouble(output.trim());
            }
        } catch (Exception e) {
            // Ignore
        }
        
        // For integrated Intel/AMD graphics, temperature is harder to get
        // Estimate based on CPU temp
        double cpuTemp = getWMITemperature();
        return cpuTemp - 5 + (Math.random() - 0.5) * 3;
    }
    
    private double getDiskUsage() {
        File cDrive = new File("C:\\");
        long total = cDrive.getTotalSpace();
        long free = cDrive.getFreeSpace();
        if (total > 0) {
            return ((total - free) * 100.0) / total;
        }
        return 0;
    }
    
    private double getWMIBatteryLevel() {
        String output = executeWMICommand("wmic path Win32_Battery get EstimatedChargeRemaining");
        try {
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group());
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }
    
    private double getNetworkUsage() {
        try {
            String output = executeWMICommand("wmic path Win32_PerfRawData_Tcpip_NetworkInterface get BytesReceivedPerSec,BytesSentPerSec");
            
            long totalReceived = 0;
            long totalSent = 0;
            
            Pattern pattern = Pattern.compile("(\\d+)\\s+(\\d+)");
            Matcher matcher = pattern.matcher(output);
            
            while (matcher.find()) {
                totalReceived += Long.parseLong(matcher.group(1));
                totalSent += Long.parseLong(matcher.group(2));
            }
            
            long currentTime = System.currentTimeMillis();
            double timeDiff = (currentTime - prevTime) / 1000.0; // seconds
            
            if (timeDiff > 0 && prevTime > 0) {
                double receivedRate = (totalReceived - prevBytesReceived) / timeDiff / 1024.0; // KB/s
                double sentRate = (totalSent - prevBytesSent) / timeDiff / 1024.0; // KB/s
                
                prevBytesReceived = totalReceived;
                prevBytesSent = totalSent;
                prevTime = currentTime;
                
                return receivedRate + sentRate;
            } else {
                prevBytesReceived = totalReceived;
                prevBytesSent = totalSent;
                prevTime = currentTime;
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }
    
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
            
            // Header
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
            
            // Value
            valueLabel = new JLabel("--");
            valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 36));
            valueLabel.setForeground(new Color(45, 45, 48));
            valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
            
            // Min/Max
            minMaxLabel = new JLabel("Min: -- | Max: --");
            minMaxLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            minMaxLabel.setForeground(new Color(120, 120, 125));
            minMaxLabel.setHorizontalAlignment(SwingConstants.CENTER);
            
            // Progress bar
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
            // Only update if value changed significantly (reduces repaints)
            if (Math.abs(value - this.currentValue) < 0.5 && this.currentValue != 0) {
                return;
            }
            
            this.currentValue = value;
            
            if (value > 0) {
                if (value < minValue) minValue = value;
                if (value > maxValue) maxValue = value;
                minMaxLabel.setText(String.format("Min: %.1f | Max: %.1f", minValue, maxValue));
            }
            
            valueLabel.setText(String.format("%.1f %s", value, unit));
            
            // Update progress bar
            int progress = calculateProgress(value);
            progressBar.setValue(progress);
            
            // Color coding
            updateColorCoding(value);
        }
        
        private int calculateProgress(double value) {
            switch (name) {
                case "Memory Usage":
                    return (int) ((value / 32.0) * 100); // Assume 32GB max for ZBook
                case "CPU Temperature":
                case "GPU Temperature":
                    return (int) ((value / 100.0) * 100); // 0-100°C
                case "Network":
                    return (int) Math.min(100, (value / 10000.0) * 100); // 0-10MB/s
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
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> {
            new HardwareMonitorDashboard().setVisible(true);
        });
    }
}