package main

import (
  "context"
  "encoding/json"
  "fmt"
  "io"
  "log"
  "net/http"
  "os"
  "os/exec"
  "runtime"
  "strconv"
  "strings"
  "sync"
  "syscall"
  "time"
)

type Net struct { Name string `json:"name"`; RXMbps float64 `json:"rx_mbps"`; TXMbps float64 `json:"tx_mbps"`; RXErrors uint64 `json:"rx_errors"`; TXErrors uint64 `json:"tx_errors"`; RXDrops uint64 `json:"rx_drops"`; TXDrops uint64 `json:"tx_drops"` }
type Disk struct { Mount string `json:"mount"`; Percent float64 `json:"percent"`; InodePercent float64 `json:"inode_percent"` }
type KV struct { Total uint64 `json:"total"`; Used uint64 `json:"used"`; Available uint64 `json:"available"` }
type Docker struct { Name string `json:"name"`; State string `json:"state"`; Status string `json:"status"` }
type Service struct { Name string `json:"name"`; Active bool `json:"active"` }
type Status struct {
  Server struct { Hostname string `json:"hostname"`; UptimeSeconds int64 `json:"uptime_seconds"` } `json:"server"`
  CPUPercent float64 `json:"cpu_percent"`
  Memory KV `json:"memory"`
  SwapPercent float64 `json:"swap_percent"`
  Disks []Disk `json:"disks"`
  Network []Net `json:"network"`
  Docker []Docker `json:"docker"`
  Services []Service `json:"services"`
  Timestamp time.Time `json:"timestamp"`
}

type cpuSnap struct { total, idle uint64 }
var prevNet = map[string][2]uint64{}
var prevTime time.Time
var mu sync.Mutex

func readCPUSnap() (cpuSnap, error) {
  b, err := os.ReadFile("/proc/stat"); if err != nil { return cpuSnap{}, err }
  for _, line := range strings.Split(string(b), "\n") {
    if strings.HasPrefix(line, "cpu ") {
      f := strings.Fields(line)[1:]
      var total uint64; for _, x := range f { n,_ := strconv.ParseUint(x,10,64); total += n }
      idle,_ := strconv.ParseUint(f[3],10,64)
      if len(f)>4 { iowait,_:=strconv.ParseUint(f[4],10,64); idle += iowait }
      return cpuSnap{total:total,idle:idle},nil
    }
  }
  return cpuSnap{}, fmt.Errorf("cpu data unavailable")
}

func cpuPercent() float64 {
  a,e1:=readCPUSnap(); time.Sleep(150*time.Millisecond); b,e2:=readCPUSnap()
  if e1!=nil||e2!=nil||b.total<=a.total { return 0 }
  dt:=float64(b.total-a.total); di:=float64(b.idle-a.idle); return (1-di/dt)*100
}

func memInfo() KV {
  b,_:=os.ReadFile("/proc/meminfo"); vals:=map[string]uint64{}
  for _,l:=range strings.Split(string(b),"\n"){ f:=strings.Fields(l); if len(f)>=2 { n,_:=strconv.ParseUint(f[1],10,64); vals[strings.TrimSuffix(f[0],":")]=n*1024 } }
  total:=vals["MemTotal"]; avail:=vals["MemAvailable"]; return KV{Total:total,Available:avail,Used:total-avail}
}

func swapPercent() float64 {
  b,_:=os.ReadFile("/proc/meminfo"); var total,free uint64
  for _,l:=range strings.Split(string(b),"\n"){ f:=strings.Fields(l); if len(f)>=2 && f[0]=="SwapTotal:" {total,_=strconv.ParseUint(f[1],10,64); total*=1024}; if len(f)>=2 && f[0]=="SwapFree:" {free,_=strconv.ParseUint(f[1],10,64); free*=1024} }
  if total==0 {return 0}; return float64(total-free)/float64(total)*100
}

func disk(m string) (Disk,bool) {
  var st syscall.Statfs_t; if syscall.Statfs(m,&st)!=nil{return Disk{},false}
  total:=uint64(st.Blocks)*uint64(st.Bsize); free:=uint64(st.Bavail)*uint64(st.Bsize); used:=total-free
  inodePct:=0.0; if st.Files>0 {inodePct=float64(st.Files-st.Ffree)/float64(st.Files)*100}
  return Disk{Mount:m,Percent:float64(used)/float64(maxu64(total,1))*100,InodePercent:inodePct},true
}
func maxu64(a,b uint64)uint64{if a>b{return a};return b}

func network() []Net {
  b,_:=os.ReadFile("/proc/net/dev"); now:=time.Now(); mu.Lock(); defer mu.Unlock()
  elapsed:=now.Sub(prevTime).Seconds(); if elapsed<=0 {elapsed=1}
  out:=[]Net{}
  for _,l:=range strings.Split(string(b),"\n"){
    if !strings.Contains(l,":"){continue}; p:=strings.SplitN(strings.TrimSpace(l),":",2); if len(p)!=2{continue}
    name:=strings.TrimSpace(p[0]); f:=strings.Fields(p[1]); if len(f)<12{continue}
    rx,_:=strconv.ParseUint(f[0],10,64); re,_:=strconv.ParseUint(f[2],10,64); rd,_:=strconv.ParseUint(f[3],10,64)
    tx,_:=strconv.ParseUint(f[8],10,64); te,_:=strconv.ParseUint(f[10],10,64); td,_:=strconv.ParseUint(f[11],10,64)
    prev,ok:=prevNet[name]; rxRate,txRate:=0.0,0.0; if ok {rxRate=float64(rx-prev[0])*8/1e6/elapsed;txRate=float64(tx-prev[1])*8/1e6/elapsed}
    prevNet[name]=[2]uint64{rx,tx}; out=append(out,Net{Name:name,RXMbps:rxRate,TXMbps:txRate,RXErrors:re,TXErrors:te,RXDrops:rd,TXDrops:td})
  }
  prevTime=now; return out
}

func docker() []Docker {
  if _,err:=exec.LookPath("docker");err!=nil{return []Docker{}}
  ctx,cancel:=context.WithTimeout(context.Background(),2*time.Second);defer cancel()
  out,err:=exec.CommandContext(ctx,"docker","ps","-a","--format","{{.Names}}|{{.State}}|{{.Status}}").Output();if err!=nil{return []Docker{}}
  var list []Docker;for _,l:=range strings.Split(strings.TrimSpace(string(out)),"\n"){p:=strings.SplitN(l,"|",3);if len(p)==3{list=append(list,Docker{Name:p[0],State:p[1],Status:p[2]})}}
  return list
}

func services() []Service {
  raw:=os.Getenv("SERVERWATCH_SERVICES");if raw==""{return []Service{}}
  var list []Service;for _,name:=range strings.Split(raw,","){name=strings.TrimSpace(name);if name==""{continue};ctx,cancel:=context.WithTimeout(context.Background(),2*time.Second);err:=exec.CommandContext(ctx,"systemctl","is-active","--quiet",name).Run();cancel();list=append(list,Service{Name:name,Active:err==nil})};return list
}

func collect() Status {
  var s Status; h,_:=os.Hostname();s.Server.Hostname=h
  up,_:=os.ReadFile("/proc/uptime"); f:=strings.Fields(string(up));if len(f)>0{v,_:=strconv.ParseFloat(f[0],64);s.Server.UptimeSeconds=int64(v)}
  s.CPUPercent=cpuPercent();s.Memory=memInfo();s.SwapPercent=swapPercent()
  for _,m:=range []string{"/","/var","/home"}{if d,ok:=disk(m);ok{s.Disks=append(s.Disks,d)}}
  s.Network=network();s.Docker=docker();s.Services=services();s.Timestamp=time.Now().UTC();return s
}

func auth(next http.Handler) http.Handler {
  token:=os.Getenv("SERVERWATCH_TOKEN")
  return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
    if token==""{http.Error(w,"agent token not configured",500);return}
    if r.Header.Get("Authorization")!="Bearer "+token{http.Error(w,"unauthorized",401);return}
    next.ServeHTTP(w,r)
  })
}

func statusHandler(w http.ResponseWriter,r *http.Request){w.Header().Set("Content-Type","application/json");json.NewEncoder(w).Encode(collect())}
func healthHandler(w http.ResponseWriter,r *http.Request){io.WriteString(w,"ok")}
func main(){
  addr:=os.Getenv("SERVERWATCH_ADDR");if addr==""{addr="0.0.0.0:8787"};if os.Getenv("SERVERWATCH_TOKEN")==""{log.Fatal("SERVERWATCH_TOKEN is required")}
  mux:=http.NewServeMux();mux.Handle("/api/v1/status",auth(http.HandlerFunc(statusHandler)));mux.HandleFunc("/health",healthHandler)
  log.Printf("ServerWatch agent listening on %s (go=%s arch=%s)",addr,runtime.Version(),runtime.GOARCH)
  log.Fatal(http.ListenAndServe(addr,mux))
}
