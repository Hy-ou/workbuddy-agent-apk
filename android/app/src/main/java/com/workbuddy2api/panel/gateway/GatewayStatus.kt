package com.workbuddy2api.panel.gateway

/** 内置网关的运行状态。 */
sealed interface GatewayStatus {
    /** 未启动。 */
    data object Stopped : GatewayStatus

    /** 正在解压/启动/等待健康检查。 */
    data class Starting(val message: String) : GatewayStatus

    /** 正在 127.0.0.1:[port] 上提供服务。 */
    data class Running(val port: Int, val apiKey: String) : GatewayStatus

    /** 启动失败（二进制缺失、端口占用、进程退出等）。 */
    data class Failed(val message: String) : GatewayStatus
}
