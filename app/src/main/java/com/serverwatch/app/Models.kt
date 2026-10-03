package com.serverwatch.app

data class ServerConfig(val name: String, val baseUrl: String, val token: String)
data class DiskMetric(val mount: String, val percent: Double, val inodePercent: Double)
data class NetworkMetric(val name:String,val rxMbps:Double,val txMbps:Double,val rxErrors:Long,val txErrors:Long,val rxDrops:Long,val txDrops:Long)
data class DockerMetric(val name:String,val state:String,val status:String)
data class ServiceMetric(val name:String,val active:Boolean)
data class ServerStatus(val hostname:String,val uptimeSeconds:Long,val cpuPercent:Double,val ramPercent:Double,val ramUsedBytes:Long,val ramTotalBytes:Long,val swapPercent:Double,val disks:List<DiskMetric>,val networks:List<NetworkMetric>,val docker:List<DockerMetric>,val services:List<ServiceMetric>) {
    val health get() = when {
        cpuPercent >=95 || ramPercent>=95 || disks.any{it.percent>=95||it.inodePercent>=95} || services.any{!it.active} || docker.any{it.state!="running"} -> "CRITICAL"
        cpuPercent >=80 || ramPercent>=80 || disks.any{it.percent>=80||it.inodePercent>=80} -> "WARNING"
        else -> "HEALTHY"
    }
}
data class ServerState(val config:ServerConfig,val status:ServerStatus?=null,val error:String?=null)
