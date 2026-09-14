package collector

import (
	"fmt"
	"math"
	"runtime"
	"sort"
	"time"

	"github.com/shirou/gopsutil/v3/process"
)

// processNameMaxLen 是单条进程名的最大长度（字符），与后端校验上限一致。
const processNameMaxLen = 128

// processReading 是单进程的原始采样值：CPU 时间片总量与内存占比。
type processReading struct {
	pid        int32
	name       string
	cpuTotal   float64
	memPercent float64
}

// ProcessSampler 周期性扫描进程表并产出 Top N 进程排行。
//
// CPU 占用用两次扫描的 CPU 时间片差分除以实际间隔计算，不调用阻塞式的
// CPUPercent(interval)；进程新建或 pid 复用（时间片总量倒退）时该进程
// 本周期按 0 计入并重置基线，避免产生畸高数值。
type ProcessSampler struct {
	topN int
	// numCPU 用于把多核时间片差分归一化到 0-100 的整机占比。
	numCPU int
	// listProcesses 提取全部进程的原始采样值，测试可注入替身。
	listProcesses func() ([]processReading, error)
	// now 返回当前时刻，测试可注入固定时钟。
	now func() time.Time

	lastCPUTotals map[int32]float64
	lastScan      time.Time
	hasLastScan   bool
}

// NewProcessSampler 创建使用真实 gopsutil API 的进程采样器；topN<=0 返回 nil。
func NewProcessSampler(topN int) *ProcessSampler {
	if topN <= 0 {
		return nil
	}
	return &ProcessSampler{
		topN:          topN,
		numCPU:        runtime.NumCPU(),
		listProcesses: listProcesses,
		now:           time.Now,
		lastCPUTotals: make(map[int32]float64),
	}
}

// Sample 返回本周期的 CPU 与内存 Top N 排行。
//
// 首次扫描只建立差分基线，返回 nil, nil 表示本周期不携带进程字段；
// 后续扫描返回降序排列的两个排行。
func (s *ProcessSampler) Sample() ([]ProcessSample, []ProcessSample, error) {
	readings, err := s.listProcesses()
	if err != nil {
		return nil, nil, fmt.Errorf("list processes: %w", err)
	}
	currentTime := s.now()

	cpuTop := make([]ProcessSample, 0, len(readings))
	memTop := make([]ProcessSample, 0, len(readings))
	elapsed := currentTime.Sub(s.lastScan).Seconds()
	for _, reading := range readings {
		cpuPercent := 0.0
		// 首个周期无基线或间隔非法时不产出 CPU 数值，按 0 计入排行。
		if s.hasLastScan && elapsed > 0 {
			if last, ok := s.lastCPUTotals[reading.pid]; ok && reading.cpuTotal >= last {
				cpuPercent = clampPercent((reading.cpuTotal-last) / elapsed / float64(s.numCPU) * 100)
			}
		}
		cpuTop = append(cpuTop, ProcessSample{
			PID:        reading.pid,
			Name:       reading.name,
			CPUPercent: round(cpuPercent),
			MemPercent: round(clampPercent(reading.memPercent)),
		})
		memTop = append(memTop, ProcessSample{
			PID:        reading.pid,
			Name:       reading.name,
			CPUPercent: round(cpuPercent),
			MemPercent: round(clampPercent(reading.memPercent)),
		})
	}

	firstScan := !s.hasLastScan
	s.resetBaselineLocked(readings, currentTime)
	if firstScan {
		return nil, nil, nil
	}
	sortSampleByCPU(cpuTop)
	sortSampleByMem(memTop)
	return truncateTop(cpuTop, s.topN), truncateTop(memTop, s.topN), nil
}

// resetBaselineLocked 用本周期采样值整体替换差分基线，消失的 pid 自动移出。
func (s *ProcessSampler) resetBaselineLocked(readings []processReading, currentTime time.Time) {
	nextTotals := make(map[int32]float64, len(readings))
	for _, reading := range readings {
		nextTotals[reading.pid] = reading.cpuTotal
	}
	s.lastCPUTotals = nextTotals
	s.lastScan = currentTime
	s.hasLastScan = true
}

// listProcesses 用 gopsutil 扫描进程表并提取原始采样值。
//
// 名称或采样值不可得的进程（权限不足、进程退出竞态）不进入排行。
func listProcesses() ([]processReading, error) {
	processes, err := process.Processes()
	if err != nil {
		return nil, err
	}
	readings := make([]processReading, 0, len(processes))
	for _, p := range processes {
		name, err := p.Name()
		if err != nil || name == "" {
			continue
		}
		times, err := p.Times()
		if err != nil {
			continue
		}
		memPercent, err := p.MemoryPercent()
		if err != nil {
			continue
		}
		readings = append(readings, processReading{
			pid:        p.Pid,
			name:       truncateProcessName(name),
			cpuTotal:   times.Total(),
			memPercent: float64(memPercent),
		})
	}
	return readings, nil
}

func sortSampleByCPU(samples []ProcessSample) {
	sort.SliceStable(samples, func(i, j int) bool { return samples[i].CPUPercent > samples[j].CPUPercent })
}

func sortSampleByMem(samples []ProcessSample) {
	sort.SliceStable(samples, func(i, j int) bool { return samples[i].MemPercent > samples[j].MemPercent })
}

func truncateTop(samples []ProcessSample, topN int) []ProcessSample {
	if len(samples) > topN {
		return samples[:topN]
	}
	return samples
}

func clampPercent(value float64) float64 {
	if math.IsNaN(value) || value < 0 {
		return 0
	}
	if value > 100 {
		return 100
	}
	return value
}

// truncateProcessName 按字符数截断进程名，避免超长内核线程名撑爆载荷。
func truncateProcessName(name string) string {
	runes := []rune(name)
	if len(runes) > processNameMaxLen {
		return string(runes[:processNameMaxLen])
	}
	return name
}
