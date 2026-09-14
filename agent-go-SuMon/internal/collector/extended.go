package collector

import (
	"sort"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/net"
)

const (
	// maxDiskEntries 是单次上报的磁盘条目上限，与后端校验和协议（websocket-protocol.md v1.5）一致。
	maxDiskEntries = 32
	// maxNicEntries 是单次上报的网卡条目上限，与后端校验和协议一致。
	maxNicEntries = 64
	// resourceNameMaxLen 是挂载点/设备名/网卡名的最大长度（字符），与协议一致。
	resourceNameMaxLen = 128
)

// excludedFSTypes 是伪文件系统否定名单：这些类型不承载持久数据，不进入磁盘容量排行。
var excludedFSTypes = map[string]bool{
	"tmpfs":    true,
	"devtmpfs": true,
	"proc":     true,
	"sysfs":    true,
	"overlay":  true,
	"squashfs": true,
	"loop":     true,
	"ramfs":    true,
}

// DiskSample 是单个挂载点的容量快照（websocket-protocol.md v1.5）。
//
// 只携带挂载点、设备名与字节数，绝不包含文件系统内容或凭据。
type DiskSample struct {
	MountPoint string
	Device     string
	TotalBytes uint64
	FreeBytes  uint64
}

// NicSample 是单个网卡的吞吐快照：采集间隔内的平均收发速率（kbps）。
type NicSample struct {
	Name   string
	RxKbps float64
	TxKbps float64
}

// partitionReading 是单个分区的原始枚举值，最小测试替身避免测试依赖平台实现细节。
type partitionReading struct {
	device     string
	mountPoint string
	fstype     string
}

// ExtendedSampler 周期性采集每挂载点磁盘容量与分网卡吞吐。
//
// 磁盘容量是点值，首周期即可上报；网卡速率用两次扫描的字节计数差分除以实际
// 间隔计算，首个周期只建立基线、不产出速率，避免把开机以来的累计字节当成瞬时速率。
// 计数回绕（网卡复位）的网卡本周期按 0 计入并重建基线，避免产生畸高速率。
type ExtendedSampler struct {
	// listPartitions 枚举物理分区原始值，测试可注入替身。
	listPartitions func() ([]partitionReading, error)
	// usage 返回挂载点的容量读数；单个分区失败不致命，跳过该分区。
	usage func(mountPoint string) (*disk.UsageStat, error)
	// listNicCounters 返回按网卡名区分的字节计数，测试可注入替身。
	listNicCounters func() ([]net.IOCountersStat, error)
	// now 返回当前时刻，测试可注入固定时钟。
	now func() time.Time

	lastNicRxTx map[string][2]uint64
	lastScan    time.Time
	hasLastScan bool
}

// NewExtendedSampler 创建使用真实 gopsutil API 的资源采样器。
//
// 磁盘侧过滤伪文件系统否定名单（Partitions(false) 只取物理设备），网卡侧
// 排除回环接口。
func NewExtendedSampler() *ExtendedSampler {
	return &ExtendedSampler{
		listPartitions: listPartitions,
		usage:          disk.Usage,
		listNicCounters: func() ([]net.IOCountersStat, error) {
			return net.IOCounters(true)
		},
		now:         time.Now,
		lastNicRxTx: make(map[string][2]uint64),
	}
}

// Sample 返回本周期的磁盘容量与网卡速率排行。
//
// 磁盘按总容量降序、网卡按接收速率降序；任一部分采集失败返回 nil，
// 不影响另一部分，也不影响指标主流程。
func (s *ExtendedSampler) Sample() ([]DiskSample, []NicSample) {
	return s.sampleDisks(), s.sampleNics()
}

// sampleDisks 采集物理分区的容量快照：过滤否定名单、按挂载点去重、
// 单分区 Usage 失败跳过，总量降序截断到协议上限。
func (s *ExtendedSampler) sampleDisks() []DiskSample {
	partitions, err := s.listPartitions()
	if err != nil {
		return nil
	}
	seen := make(map[string]bool, len(partitions))
	samples := make([]DiskSample, 0, len(partitions))
	for _, partition := range partitions {
		if excludedFSTypes[strings.ToLower(partition.fstype)] || seen[partition.mountPoint] {
			continue
		}
		seen[partition.mountPoint] = true
		usage, usageErr := s.usage(partition.mountPoint)
		if usageErr != nil || usage == nil {
			continue
		}
		samples = append(samples, DiskSample{
			MountPoint: truncateResourceName(partition.mountPoint),
			Device:     truncateResourceName(partition.device),
			TotalBytes: usage.Total,
			FreeBytes:  usage.Free,
		})
	}
	sort.SliceStable(samples, func(i, j int) bool { return samples[i].TotalBytes > samples[j].TotalBytes })
	return truncateDisks(samples, maxDiskEntries)
}

// sampleNics 采集分网卡吞吐：排除回环接口，首个周期仅建立差分基线返回 nil。
func (s *ExtendedSampler) sampleNics() []NicSample {
	counters, err := s.listNicCounters()
	if err != nil {
		return nil
	}
	currentTime := s.now()

	current := make(map[string][2]uint64, len(counters))
	for _, counter := range counters {
		if counter.Name == "lo" {
			continue
		}
		current[counter.Name] = [2]uint64{counter.BytesRecv, counter.BytesSent}
	}

	elapsed := currentTime.Sub(s.lastScan).Seconds()
	previous := s.lastNicRxTx
	firstScan := !s.hasLastScan
	s.lastNicRxTx = current
	s.lastScan = currentTime
	s.hasLastScan = true
	if firstScan || elapsed <= 0 {
		return nil
	}
	return computeNicRates(current, previous, elapsed)
}

// computeNicRates 用本周期与上一周期字节计数差分计算 kbps 速率：
// 速率 = Δbytes × 8 ÷ Δsec ÷ 1000，保留 2 位小数；新出现或计数回绕的网卡
// 本周期按 0 计入。结果按接收速率降序截断到协议上限。
func computeNicRates(current, previous map[string][2]uint64, elapsedSeconds float64) []NicSample {
	samples := make([]NicSample, 0, len(current))
	for name, rxTx := range current {
		last, ok := previous[name]
		if !ok || rxTx[0] < last[0] || rxTx[1] < last[1] {
			samples = append(samples, NicSample{Name: truncateResourceName(name)})
			continue
		}
		samples = append(samples, NicSample{
			Name:   truncateResourceName(name),
			RxKbps: round(float64(rxTx[0]-last[0]) * 8 / elapsedSeconds / 1000),
			TxKbps: round(float64(rxTx[1]-last[1]) * 8 / elapsedSeconds / 1000),
		})
	}
	sort.SliceStable(samples, func(i, j int) bool { return samples[i].RxKbps > samples[j].RxKbps })
	return truncateNics(samples, maxNicEntries)
}

// listPartitions 枚举物理分区并转换为采集器内部类型；枚举失败由调用方整体跳过。
func listPartitions() ([]partitionReading, error) {
	partitions, err := disk.Partitions(false)
	if err != nil {
		return nil, err
	}
	readings := make([]partitionReading, 0, len(partitions))
	for _, partition := range partitions {
		readings = append(readings, partitionReading{
			device:     partition.Device,
			mountPoint: partition.Mountpoint,
			fstype:     partition.Fstype,
		})
	}
	return readings, nil
}

// truncateDisks 截断磁盘条目到协议上限。
func truncateDisks(samples []DiskSample, limit int) []DiskSample {
	if len(samples) > limit {
		return samples[:limit]
	}
	return samples
}

// truncateNics 截断网卡条目到协议上限。
func truncateNics(samples []NicSample, limit int) []NicSample {
	if len(samples) > limit {
		return samples[:limit]
	}
	return samples
}

// truncateResourceName 按字符数截断挂载点/设备名/网卡名，避免超长路径撑爆载荷。
func truncateResourceName(name string) string {
	runes := []rune(name)
	if len(runes) > resourceNameMaxLen {
		return string(runes[:resourceNameMaxLen])
	}
	return name
}
