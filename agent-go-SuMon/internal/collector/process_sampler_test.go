package collector

import (
	"errors"
	"testing"
	"time"
)

// samplerFixture 构造带注入点的采样器，scan 推进一个扫描周期。
type samplerFixture struct {
	sampler  *ProcessSampler
	readings [][]processReading
	times    []time.Time
	scan     int
	now      time.Time
	err      error
}

func newSamplerFixture(topN int, now time.Time) *samplerFixture {
	fixture := &samplerFixture{now: now}
	fixture.sampler = &ProcessSampler{
		topN:          topN,
		numCPU:        2,
		listProcesses: func() ([]processReading, error) { return fixture.scanProcesses() },
		now:           func() time.Time { return fixture.currentTime() },
		lastCPUTotals: make(map[int32]float64),
	}
	return fixture
}

func (f *samplerFixture) scanProcesses() ([]processReading, error) {
	if f.err != nil {
		return nil, f.err
	}
	if f.scan >= len(f.readings) {
		f.scan++
		return f.readings[len(f.readings)-1], nil
	}
	readings := f.readings[f.scan]
	f.scan++
	return readings, nil
}

func (f *samplerFixture) currentTime() time.Time {
	if f.scan > 0 && f.scan <= len(f.times) {
		return f.times[f.scan-1]
	}
	return f.now
}

// advance 推进到下一个扫描周期并返回 Sample 结果。
func (f *samplerFixture) advance(readings []processReading, at time.Time) ([]ProcessSample, []ProcessSample, error) {
	f.readings = append(f.readings, readings)
	f.times = append(f.times, at)
	return f.sampler.Sample()
}

func TestProcessSamplerFirstScanBuildsBaselineOnly(t *testing.T) {
	fixture := newSamplerFixture(10, time.Unix(1000, 0))

	cpuTop, memTop, err := fixture.advance([]processReading{
		{pid: 1, name: "init", cpuTotal: 10, memPercent: 5},
	}, time.Unix(1005, 0))

	if err != nil {
		t.Fatalf("Sample() error = %v", err)
	}
	if cpuTop != nil || memTop != nil {
		t.Fatalf("first scan should not carry rankings, got cpu=%v mem=%v", cpuTop, memTop)
	}
}

func TestProcessSamplerComputesCPUDeltaNormalized(t *testing.T) {
	fixture := newSamplerFixture(10, time.Unix(1000, 0))
	if _, _, err := fixture.advance([]processReading{
		{pid: 1, name: "worker", cpuTotal: 10, memPercent: 12.5},
		{pid: 2, name: "idle", cpuTotal: 0, memPercent: 30},
	}, time.Unix(1000, 0)); err != nil {
		t.Fatalf("baseline scan error = %v", err)
	}

	// 2 核机器、5 秒间隔：pid 1 消耗 1 CPU 秒 => 1/(5*2)*100 = 10%。
	cpuTop, memTop, err := fixture.advance([]processReading{
		{pid: 1, name: "worker", cpuTotal: 11, memPercent: 12.5},
		{pid: 2, name: "idle", cpuTotal: 0, memPercent: 30},
	}, time.Unix(1005, 0))
	if err != nil {
		t.Fatalf("Sample() error = %v", err)
	}
	if len(cpuTop) != 2 || len(memTop) != 2 {
		t.Fatalf("unexpected ranking sizes: cpu=%d mem=%d", len(cpuTop), len(memTop))
	}
	if cpuTop[0].PID != 1 || cpuTop[0].CPUPercent != 10 {
		t.Fatalf("cpu ranking mismatch: %+v", cpuTop)
	}
	if memTop[0].PID != 2 || memTop[0].MemPercent != 30 {
		t.Fatalf("mem ranking mismatch: %+v", memTop)
	}
}

func TestProcessSamplerResetsBaselineOnPidReuse(t *testing.T) {
	fixture := newSamplerFixture(10, time.Unix(1000, 0))
	if _, _, err := fixture.advance([]processReading{
		{pid: 7, name: "old", cpuTotal: 500, memPercent: 1},
	}, time.Unix(1000, 0)); err != nil {
		t.Fatalf("baseline scan error = %v", err)
	}

	// pid 7 被复用且时间片总量倒退：本周期 CPU 按 0 计入，不产生畸高数值。
	cpuTop, _, err := fixture.advance([]processReading{
		{pid: 7, name: "new", cpuTotal: 1, memPercent: 1},
	}, time.Unix(1005, 0))
	if err != nil {
		t.Fatalf("Sample() error = %v", err)
	}
	if len(cpuTop) != 1 || cpuTop[0].Name != "new" || cpuTop[0].CPUPercent != 0 {
		t.Fatalf("pid reuse should yield zero cpu percent: %+v", cpuTop)
	}
}

func TestProcessSamplerTruncatesToTopNAndSortsDescending(t *testing.T) {
	fixture := newSamplerFixture(2, time.Unix(1000, 0))
	if _, _, err := fixture.advance([]processReading{
		{pid: 1, name: "a", cpuTotal: 0, memPercent: 1},
		{pid: 2, name: "b", cpuTotal: 0, memPercent: 1},
		{pid: 3, name: "c", cpuTotal: 0, memPercent: 1},
	}, time.Unix(1000, 0)); err != nil {
		t.Fatalf("baseline scan error = %v", err)
	}

	cpuTop, memTop, err := fixture.advance([]processReading{
		{pid: 1, name: "a", cpuTotal: 0.4, memPercent: 5},
		{pid: 2, name: "b", cpuTotal: 0.8, memPercent: 50},
		{pid: 3, name: "c", cpuTotal: 0.6, memPercent: 20},
	}, time.Unix(1004, 0))
	if err != nil {
		t.Fatalf("Sample() error = %v", err)
	}
	if len(cpuTop) != 2 || len(memTop) != 2 {
		t.Fatalf("topN truncation failed: cpu=%d mem=%d", len(cpuTop), len(memTop))
	}
	if cpuTop[0].PID != 2 || cpuTop[1].PID != 3 {
		t.Fatalf("cpu ranking order mismatch: %+v", cpuTop)
	}
	if memTop[0].PID != 2 || memTop[1].PID != 3 {
		t.Fatalf("mem ranking order mismatch: %+v", memTop)
	}
}

func TestProcessSamplerPropagatesListError(t *testing.T) {
	fixture := newSamplerFixture(10, time.Unix(1000, 0))
	fixture.err = errors.New("proc walk failed")

	if _, _, err := fixture.advance(nil, time.Unix(1005, 0)); err == nil {
		t.Fatalf("Sample() error = nil, want error")
	}
}

func TestNewProcessSamplerZeroDisables(t *testing.T) {
	if NewProcessSampler(0) != nil {
		t.Fatalf("NewProcessSampler(0) should return nil to disable collection")
	}
}

func TestTruncateProcessNameCapsLength(t *testing.T) {
	longName := make([]rune, processNameMaxLen+10)
	for i := range longName {
		longName[i] = 'x'
	}
	if got := truncateProcessName(string(longName)); len([]rune(got)) != processNameMaxLen {
		t.Fatalf("truncateProcessName length = %d, want %d", len([]rune(got)), processNameMaxLen)
	}
	if got := truncateProcessName("java"); got != "java" {
		t.Fatalf("short name should be unchanged, got %q", got)
	}
}
