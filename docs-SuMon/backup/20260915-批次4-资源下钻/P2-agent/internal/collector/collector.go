// Package collector 定义系统指标采集能力。
//
// gopsutil 实现负责跨平台系统指标采集；Windows 上 temperature 和 load_avg
// 通常无法采集，对应字段为 nil。
package collector

// Metrics 是一次采集的系统指标快照，与后端 metrics 固定宽表字段一一对应。
//
// 指针类型字段表示可空；nil 序列化为 JSON null。
type Metrics struct {
	CPUPercent    *float64
	MemoryPercent *float64
	MemoryUsed    *uint64
	MemoryTotal   *uint64
	DiskPercent   *float64
	DiskUsed      *uint64
	DiskTotal     *uint64
	NetRx         *uint64
	NetTx         *uint64
	Temperature   *float64
	LoadAvg       *float64
	// ProcessCPUTop 是按 CPU 占用降序的 Top 进程；nil 表示本周期未采集
	//（未启用、首个采样周期尚无差分基线或采集失败）。
	ProcessCPUTop *[]ProcessSample
	// ProcessMemTop 是按内存占比降序的 Top 进程；nil 含义同 ProcessCPUTop。
	ProcessMemTop *[]ProcessSample
}

// ProcessSample 是单个进程的占用快照，用于 Top 进程排行。
//
// 只携带进程名与占用比例，绝不包含命令行与环境变量（websocket-protocol.md v1.4）。
type ProcessSample struct {
	PID        int32
	Name       string
	CPUPercent float64
	MemPercent float64
}

// Collector 是系统指标采集接口。
//
// 实现方负责跨平台差异处理；调用方按固定间隔调用 Collect 获取快照。
type Collector interface {
	// Collect 采集一次系统指标快照。
	Collect() (Metrics, error)
}
