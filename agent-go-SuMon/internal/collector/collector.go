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
}

// Collector 是系统指标采集接口。
//
// 实现方负责跨平台差异处理；调用方按固定间隔调用 Collect 获取快照。
type Collector interface {
	// Collect 采集一次系统指标快照。
	Collect() (Metrics, error)
}
