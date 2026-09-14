package collector

import (
	"errors"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/net"
)

// testExtendedSampler 构造注入假数据源的资源采样器。
func testExtendedSampler(partitions []partitionReading, partitionErr error, usageErr map[string]error,
	counters []net.IOCountersStat, counterErr error) *ExtendedSampler {
	return &ExtendedSampler{
		listPartitions: func() ([]partitionReading, error) { return partitions, partitionErr },
		usage: func(mountPoint string) (*disk.UsageStat, error) {
			if usageErr != nil {
				if err, ok := usageErr[mountPoint]; ok {
					return nil, err
				}
			}
			return &disk.UsageStat{Total: 1000, Free: 250}, nil
		},
		listNicCounters: func() ([]net.IOCountersStat, error) { return counters, counterErr },
		now:             time.Now,
		lastNicRxTx:     make(map[string][2]uint64),
	}
}

// fixedClockSampler 返回每次调用前进 5 秒的采样器，供差分用例复用。
func fixedClockSampler(s *ExtendedSampler) *ExtendedSampler {
	fixed := time.Unix(1000, 0)
	s.now = func() time.Time { fixed = fixed.Add(5 * time.Second); return fixed }
	return s
}

func TestExtendedSamplerFiltersPseudoFilesystemsAndDeduplicates(t *testing.T) {
	s := testExtendedSampler([]partitionReading{
		{device: "/dev/sda1", mountPoint: "/", fstype: "ext4"},
		{device: "tmpfs", mountPoint: "/run", fstype: "tmpfs"},
		{device: "overlay", mountPoint: "/var/lib/docker", fstype: "overlay"},
		{device: "/dev/sdb1", mountPoint: "/data", fstype: "EXT4"},
		{device: "/dev/sda1", mountPoint: "/", fstype: "ext4"},
	}, nil, nil, nil, nil)

	disks, nics := s.Sample()
	if nics != nil {
		t.Fatalf("no counters configured; nics should be nil: %+v", nics)
	}
	if len(disks) != 2 {
		t.Fatalf("expected 2 disks after filtering and dedup, got %d: %+v", len(disks), disks)
	}
	// 容量相同时保持稳定顺序：/ 在前。
	if disks[0].MountPoint != "/" || disks[1].MountPoint != "/data" {
		t.Fatalf("unexpected disk order: %+v", disks)
	}
	if disks[0].Device != "/dev/sda1" || disks[0].TotalBytes != 1000 || disks[0].FreeBytes != 250 {
		t.Fatalf("unexpected disk fields: %+v", disks[0])
	}
}

func TestExtendedSamplerSkipsFailedUsage(t *testing.T) {
	s := testExtendedSampler([]partitionReading{
		{device: "/dev/sda1", mountPoint: "/", fstype: "ext4"},
		{device: "/dev/sdb1", mountPoint: "/broken", fstype: "ext4"},
	}, nil, map[string]error{"/broken": errors.New("permission denied")}, nil, nil)

	disks, _ := s.Sample()
	if len(disks) != 1 || disks[0].MountPoint != "/" {
		t.Fatalf("failed partition should be skipped: %+v", disks)
	}
}

func TestExtendedSamplerTruncatesDisksToLimit(t *testing.T) {
	// 37 个分区（上限+5），容量随下标递增；降序截断后应保留容量最大的 32 个。
	partitions := make([]partitionReading, maxDiskEntries+5)
	for i := range partitions {
		partitions[i] = partitionReading{
			device:     "/dev/sd" + string(rune('a'+i%26)),
			mountPoint: "/mnt/d" + string(rune('a'+i)),
			fstype:     "ext4",
		}
	}
	s := testExtendedSampler(partitions, nil, nil, nil, nil)
	// 容量随枚举顺序递增：第 index 次调用返回 1000+index，间接模拟"下标越大容量越大"。
	index := 0
	s.usage = func(string) (*disk.UsageStat, error) {
		index++
		return &disk.UsageStat{Total: uint64(1000 + index), Free: 1}, nil
	}

	disks, _ := s.Sample()
	if len(disks) != maxDiskEntries {
		t.Fatalf("expected %d disks, got %d", maxDiskEntries, len(disks))
	}
	if disks[0].TotalBytes != 1000+maxDiskEntries+5 {
		t.Fatalf("largest partition should be first: %+v", disks[0])
	}
	// 截断保留最大的 32 个：最后保留的是第 6 小的分区（下标 5，容量 1006）。
	if disks[maxDiskEntries-1].TotalBytes != 1006 {
		t.Fatalf("truncation should keep the largest partitions: last=%+v", disks[maxDiskEntries-1])
	}
}

func TestExtendedSamplerTruncatesResourceNames(t *testing.T) {
	longName := strings.Repeat("m", resourceNameMaxLen+50)
	s := testExtendedSampler([]partitionReading{
		{device: "/dev/sda1", mountPoint: longName, fstype: "ext4"},
	}, nil, nil, []net.IOCountersStat{{Name: longName, BytesRecv: 100, BytesSent: 100}}, nil)

	disks, _ := s.Sample()
	if len(disks) != 1 || len([]rune(disks[0].MountPoint)) != resourceNameMaxLen {
		t.Fatalf("mount point should be truncated to %d runes: %+v", resourceNameMaxLen, disks)
	}
	if disks[0].Device != "/dev/sda1" {
		t.Fatalf("short device name should be untouched: %+v", disks[0])
	}
}

func TestExtendedSamplerNicRatesNeedBaselineAndDiffOverFiveSeconds(t *testing.T) {
	// 首周期基线：eth0 recv 100000 / tx 50000，eth1 recv 200000 / tx 80000，lo 被排除。
	counters := []net.IOCountersStat{
		{Name: "lo", BytesRecv: 999999, BytesSent: 999999},
		{Name: "eth0", BytesRecv: 100000, BytesSent: 50000},
		{Name: "eth1", BytesRecv: 200000, BytesSent: 80000},
	}
	s := fixedClockSampler(testExtendedSampler(nil, nil, nil, counters, nil))

	if _, nics := s.Sample(); nics != nil {
		t.Fatalf("first cycle should only build the baseline: %+v", nics)
	}

	// 5 秒差分：eth1 recv +600000 => 960 kbps，tx +8000 => 12.8；eth0 recv +300000 => 480。
	counters[1] = net.IOCountersStat{Name: "eth0", BytesRecv: 400000, BytesSent: 50000}
	counters[2] = net.IOCountersStat{Name: "eth1", BytesRecv: 800000, BytesSent: 88000}
	_, nics := s.Sample()
	if len(nics) != 2 {
		t.Fatalf("expected 2 nics (lo excluded), got %d: %+v", len(nics), nics)
	}
	if nics[0].Name != "eth1" || nics[0].RxKbps != 960 || nics[0].TxKbps != 12.8 {
		t.Fatalf("nics should be sorted by rx desc: %+v", nics[0])
	}
	if nics[1].Name != "eth0" || nics[1].RxKbps != 480 || nics[1].TxKbps != 0 {
		t.Fatalf("unexpected nic rates: %+v", nics[1])
	}
}

func TestExtendedSamplerNicCounterWraparoundCountsZero(t *testing.T) {
	counters := []net.IOCountersStat{{Name: "eth0", BytesRecv: 100000, BytesSent: 100000}}
	s := fixedClockSampler(testExtendedSampler(nil, nil, nil, counters, nil))
	_, _ = s.Sample()

	counters[0] = net.IOCountersStat{Name: "eth0", BytesRecv: 50, BytesSent: 60}
	_, nics := s.Sample()
	if len(nics) != 1 || nics[0].Name != "eth0" || nics[0].RxKbps != 0 || nics[0].TxKbps != 0 {
		t.Fatalf("wraparound should count as zero for the cycle: %+v", nics)
	}
}

func TestExtendedSamplerNicDiffRoundsToTwoDecimals(t *testing.T) {
	// 3333 bytes over 5s => 3333*8/5/1000 = 5.3328 kbps => 5.33。
	counters := []net.IOCountersStat{{Name: "eth0", BytesRecv: 0, BytesSent: 0}}
	s := fixedClockSampler(testExtendedSampler(nil, nil, nil, counters, nil))
	_, _ = s.Sample()

	counters[0] = net.IOCountersStat{Name: "eth0", BytesRecv: 3333, BytesSent: 0}
	_, nics := s.Sample()
	if len(nics) != 1 || math.Abs(nics[0].RxKbps-5.33) > 0.0001 {
		t.Fatalf("expected 5.33 kbps, got %+v", nics)
	}
}

func TestExtendedSamplerFailuresKeepPartsIndependent(t *testing.T) {
	s := fixedClockSampler(testExtendedSampler(nil, errors.New("partitions failed"), nil,
		[]net.IOCountersStat{{Name: "eth0", BytesRecv: 100, BytesSent: 100}}, nil))
	disks, _ := s.Sample()
	if disks != nil {
		t.Fatalf("partition failure should yield nil disks: %+v", disks)
	}

	s2 := testExtendedSampler([]partitionReading{{device: "/dev/sda1", mountPoint: "/", fstype: "ext4"}},
		nil, nil, nil, errors.New("counters failed"))
	disks2, nics2 := s2.Sample()
	if len(disks2) != 1 || nics2 != nil {
		t.Fatalf("counter failure should yield nil nics but keep disks: %+v %+v", disks2, nics2)
	}
}
