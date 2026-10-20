package com.workbuddy2api.panel

import com.workbuddy2api.panel.data.ConfigEnvelope
import com.workbuddy2api.panel.data.LoginRegions
import com.workbuddy2api.panel.data.LogsPage
import com.workbuddy2api.panel.data.ModelProbes
import com.workbuddy2api.panel.data.Overview
import com.workbuddy2api.panel.data.PackagesPage
import com.workbuddy2api.panel.data.QueueStatus
import com.workbuddy2api.panel.data.RequestLogsPage
import com.workbuddy2api.panel.data.RequestMetrics
import com.workbuddy2api.panel.data.SchoolVouchersPage
import com.workbuddy2api.panel.data.UsageSnapshot
import com.workbuddy2api.panel.data.WbJson
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 用真机内置网关的真实响应验证所有 REST 模型的解析（防止字段类型漂移导致整页报错）。 */
class LiveDecodeTest {

    private fun body(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("live/$name")
            ?: error("missing fixture: $name")
        return stream.bufferedReader().use { it.readText() }.removePrefix("\uFEFF")
    }

    private inline fun <reified T> decode(name: String): T = WbJson.decodeFromString<T>(body(name))

    @Test fun overviewParses() {
        val o = decode<Overview>("overview.json")
        assertNotNull(o.accounts)
        assertNotNull(o.version)
    }

    @Test fun logsParse() = assertNotNull(decode<LogsPage>("logs.json").entries)

    @Test fun metricsParse() = assertNotNull(decode<RequestMetrics>("request_metrics.json"))

    @Test fun requestLogsParse() = assertNotNull(decode<RequestLogsPage>("request_logs_limit_5.json").entries)

    @Test fun queueParse() = assertNotNull(decode<QueueStatus>("tasks_queue.json"))

    @Test fun configParse() = assertNotNull(decode<ConfigEnvelope>("config.json").config)

    @Test fun packagesParse() = assertNotNull(decode<PackagesPage>("packages.json").accounts)

    @Test fun usageParse() = assertNotNull(decode<UsageSnapshot>("usage.json").totals)

    @Test fun vouchersParse() = assertNotNull(decode<SchoolVouchersPage>("school_vouchers.json").accounts)

    @Test fun probesParse() = assertNotNull(decode<ModelProbes>("model_probes.json"))

    @Test fun regionsParse() = assertNotNull(decode<LoginRegions>("login_regions.json").regions)
}
