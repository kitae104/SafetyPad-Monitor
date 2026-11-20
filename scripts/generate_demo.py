#!/usr/bin/env python3
"""Deterministic, explicitly fictional Inha Technical College-area IoT demo records."""
import csv,json,random
from datetime import datetime,timedelta,timezone
from pathlib import Path
r=random.Random(20261107)
root=Path(__file__).resolve().parents[1]/'data';root.mkdir(exist_ok=True)
KST=timezone(timedelta(hours=9));start=datetime(2026,11,1,8,0,tzinfo=KST)
# Approximate demo anchor, not actual device locations or real travel trajectories.
lat0,lon0=37.451448,126.651542
names=['인하공전 주변 북측','인하공전 주변 동측','인하공전 주변 남측','인하공전 주변 서측','용현동 일대']
devices=[];events=[];locations=[]
for i in range(1,51):
    device=f'PAD-{i:04d}'
    base_lat=lat0+r.uniform(-.008,.008);base_lon=lon0+r.uniform(-.010,.010)
    device_meta={'device_id':device,'alias':f'테스트 발판 {i:02d}','zone':names[(i-1)%len(names)],'active':True,'battery_level':r.randint(16,99),'simulated':True}
    devices.append(device_meta)
    # 5 days of sparse synthetic trajectory pings, every 15 minutes for 2 hours only.
    path=[]
    for day in range(5):
        for k in range(9):
            t=start+timedelta(days=day,minutes=k*15, hours=i%5)
            lat=round(base_lat+.0006*k+r.uniform(-.00015,.00015),6)
            lon=round(base_lon+.00035*k+r.uniform(-.00015,.00015),6)
            p={'device_id':device,'recorded_at':t.isoformat(),'latitude':lat,'longitude':lon,'source':'DEMO_GPS_PING','simulated':True}
            locations.append(p);path.append(p)
    # 2-5 violations per device; sometimes late and sometimes no GPS.
    for j in range(r.randint(2,5)):
        point=r.choice(path); t=datetime.fromisoformat(point['recorded_at'])+timedelta(seconds=r.randint(1,80))
        lag=r.choice([2,3,8,12,34,120,480,1200]);gps=None if r.random()<.08 else {'latitude':point['latitude'],'longitude':point['longitude']}
        events.append({'event_id':f'{device}-EVT-{j+1:05d}','device_id':device,'event_type':'MULTI_RIDE','event_time':t.isoformat(),'received_at':(t+timedelta(seconds=lag)).isoformat(),'gpsData':gps,'battery_level':max(0,device_meta['battery_level']-j),'simulated':True})
# Order by event time for UI sorting demo
events.sort(key=lambda e:e['event_time'],reverse=True)
for filename,obj in [('devices.json',devices),('events.json',events),('gps_locations.json',locations)]:
    (root/filename).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
with (root/'events.csv').open('w',encoding='utf-8-sig',newline='') as f:
    w=csv.writer(f);w.writerow(['event_id','device_id','event_type','event_time','received_at','latitude','longitude','battery_level','simulated'])
    for e in events:
        gps=e['gpsData'] or {};w.writerow([e['event_id'],e['device_id'],e['event_type'],e['event_time'],e['received_at'],gps.get('latitude',''),gps.get('longitude',''),e['battery_level'],True])
print(f'Created {len(devices)} fictional devices, {len(events)} event records, {len(locations)} GPS samples.')
