package com.serverwatch.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object Api {
    fun fetch(config:ServerConfig):ServerStatus{
        val c=(URL(config.baseUrl.trimEnd('/')+"/api/v1/status").openConnection() as HttpURLConnection).apply{
            connectTimeout=5000;readTimeout=8000;requestMethod="GET";setRequestProperty("Accept","application/json");setRequestProperty("Authorization","Bearer ${config.token}")
        }
        try{if(c.responseCode !in 200..299) error("HTTP ${c.responseCode}");return parse(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
    }
    private fun parse(raw:String):ServerStatus{
        val o=JSONObject(raw);val m=o.getJSONObject("memory");val total=max(1L,m.getLong("total"));val used=m.optLong("used",total-m.optLong("available"));val ramPercent=o.optDouble("ram_percent",(used.toDouble()/total.toDouble())*100.0)
        val disks=o.optJSONArray("disks")?.let{a->(0 until a.length()).map{a.getJSONObject(it).let{x->DiskMetric(x.getString("mount"),x.getDouble("percent"),x.optDouble("inode_percent"))}}}?:emptyList()
        val nets=o.optJSONArray("network")?.let{a->(0 until a.length()).map{a.getJSONObject(it).let{x->NetworkMetric(x.getString("name"),x.optDouble("rx_mbps"),x.optDouble("tx_mbps"),x.optLong("rx_errors"),x.optLong("tx_errors"),x.optLong("rx_drops"),x.optLong("tx_drops"))}}}?:emptyList()
        val dock=o.optJSONArray("docker")?.let{a->(0 until a.length()).map{a.getJSONObject(it).let{x->DockerMetric(x.getString("name"),x.getString("state"),x.optString("status"))}}}?:emptyList()
        val svc=o.optJSONArray("services")?.let{a->(0 until a.length()).map{a.getJSONObject(it).let{x->ServiceMetric(x.getString("name"),x.optBoolean("active"))}}}?:emptyList()
        val s=o.getJSONObject("server")
        return ServerStatus(s.getString("hostname"),s.getLong("uptime_seconds"),o.getDouble("cpu_percent"),ramPercent,used,total,o.optDouble("swap_percent"),disks,nets,dock,svc)
    }
}
