# Hardware Dashboard

A cross-platform Java Swing desktop application for real-time hardware monitoring. Displays CPU, memory, GPU, disk, battery, and network metrics with colour-coded progress bars and min/max tracking.

## Features

- **CPU usage**: Sampled via `/proc/stat` (Linux) or WMI (Windows)
- **CPU temperature**: Reads from `/sys/class/thermal/` or hwmon (`coretemp`, `k10temp`, `zenpower`) on Linux; WMI thermal zones on Windows
- **Memory**: Used GB and percentage via `com.sun.management.OperatingSystemMXBean` (cross-platform)
- **GPU temperature**: NVIDIA via `nvidia-smi` (cross-platform); AMD via hwmon `amdgpu` (Linux)
- **Disk usage**: Root filesystem on Linux (`/`), C: drive on Windows
- **Battery level**: `/sys/class/power_supply/BAT0/capacity` on Linux; WMI `Win32_Battery` on Windows
- **Network throughput**: Aggregated KB/s across all interfaces via `/proc/net/dev` (Linux) or WMI (Windows)
- **Configurable refresh**: 5 s, 10 s, 15 s, 30 s, or 60 s intervals
- **Min/max tracking**: Per-metric historical low and high displayed beneath each value
- **Colour coding**: Progress bars turn yellow or red as values approach critical thresholds

## Prerequisites

- Java 24 or higher
- Linux or Windows
- `nvidia-smi` in PATH for NVIDIA GPU temperature (optional)

## Build

```bash
mvn clean package
```

Produces `target/hardware-dashboard-all.jar`.

## Run

```bash
java -jar target/hardware-dashboard-all.jar
```

## Project Structure

```
src/main/java/com/ourgiant/hardware/
└── HardwareMonitorDashboard.java    # Main application, metric collection, UI
```

## License

See LICENSE file for details.
