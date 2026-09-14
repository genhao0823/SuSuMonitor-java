package collector

import (
	"fmt"
	"math"
	"runtime"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/host"
	"github.com/shirou/gopsutil/v3/load"
	"github.com/shirou/gopsutil/v3/mem"
	"github.com/shirou/gopsutil/v3/net"
)

const cpuSampleInterval = time.Second

// GopsutilCollector 使用 gopsutil 采集当前主机的系统指标。
// 可选的温度和负载指标不可用时返回 nil，不影响其他指标上报。
type GopsutilCollector struct {
	cpuPercent          func(time.Duration, bool) ([]float64, error)
	virtualMemory       func() (*mem.VirtualMemoryStat, error)
	diskUsage           func(string) (*disk.UsageStat, error)
	netIOCounters       func(bool) ([]net.IOCountersStat, error)
	sensorsTemperatures func() ([]sensorTemperature, error)
	loadAvg             func() (*load.AvgStat, error)
}

// sensorTemperature 是 SensorsTemperatures 的最小测试替身，避免测试依赖平台实现细节。
type sensorTemperature struct {
	Temperature float64
}

// NewGopsutilCollector 创建使用真实 gopsutil API 的采集器。
func NewGopsutilCollector() *GopsutilCollector {
	return &GopsutilCollector{
		cpuPercent:          cpu.Percent,
		virtualMemory:       mem.VirtualMemory,
		diskUsage:           disk.Usage,
		netIOCounters:       net.IOCounters,
		sensorsTemperatures: sensorTemperatures,
		loadAvg:             load.Avg,
	}
}

// Collect 采集一次系统指标快照。
func (c *GopsutilCollector) Collect() (Metrics, error) {
	cpuValues, err := c.cpuPercent(cpuSampleInterval, false)
	if err != nil {
		return Metrics{}, fmt.Errorf("collect cpu: %w", err)
	}
	if len(cpuValues) == 0 {
		return Metrics{}, fmt.Errorf("collect cpu: no values returned")
	}

	memory, err := c.virtualMemory()
	if err != nil {
		return Metrics{}, fmt.Errorf("collect memory: %w", err)
	}
	diskPath := systemDiskPath()
	diskInfo, err := c.diskUsage(diskPath)
	if err != nil {
		return Metrics{}, fmt.Errorf("collect disk %s: %w", diskPath, err)
	}
	network, err := c.netIOCounters(false)
	if err != nil {
		return Metrics{}, fmt.Errorf("collect network: %w", err)
	}
	if len(network) == 0 {
		return Metrics{}, fmt.Errorf("collect network: no counters returned")
	}

	metrics := Metrics{
		CPUPercent:    float64Pointer(round(cpuValues[0])),
		MemoryPercent: float64Pointer(round(memory.UsedPercent)),
		MemoryUsed:    uint64Pointer(memory.Used),
		MemoryTotal:   uint64Pointer(memory.Total),
		DiskPercent:   float64Pointer(round(diskInfo.UsedPercent)),
		DiskUsed:      uint64Pointer(diskInfo.Used),
		DiskTotal:     uint64Pointer(diskInfo.Total),
		NetRx:         uint64Pointer(network[0].BytesRecv),
		NetTx:         uint64Pointer(network[0].BytesSent),
	}

	if temperatures, temperatureErr := c.sensorsTemperatures(); temperatureErr == nil && len(temperatures) > 0 {
		metrics.Temperature = float64Pointer(round(temperatures[0].Temperature))
	}
	if loadInfo, loadErr := c.loadAvg(); loadErr == nil && loadInfo != nil {
		metrics.LoadAvg = float64Pointer(round(loadInfo.Load1))
	}
	return metrics, nil
}

func systemDiskPath() string {
	if runtime.GOOS == "windows" {
		return "C:"
	}
	return "/"
}

func round(value float64) float64 {
	return math.Round(value*100) / 100
}

func float64Pointer(value float64) *float64 { return &value }

func uint64Pointer(value uint64) *uint64 { return &value }

// sensorTemperatures 将平台相关的 gopsutil 温度类型转换为采集器内部类型。
func sensorTemperatures() ([]sensorTemperature, error) {
	values, err := host.SensorsTemperatures()
	if err != nil {
		return nil, err
	}
	result := make([]sensorTemperature, len(values))
	for i, value := range values {
		result[i].Temperature = value.Temperature
	}
	return result, nil
}
