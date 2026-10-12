"""Render architecture PNGs with Pillow: python render_diagrams.py."""
from pathlib import Path
import math
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
W = 1600
INK = '#17324d'
MUTED = '#50677b'
BLUE = '#245d9b'
TEAL = '#18756b'
BG = '#f4f7fb'
FONT_PATH = next((p for p in [Path('/System/Library/Fonts/Supplemental/Arial.ttf'), Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')] if p.exists()), None)
def font(size):
    return ImageFont.truetype(str(FONT_PATH), size) if FONT_PATH else ImageFont.load_default()
def text(d, xy, value, size=25, fill=INK, anchor=None):
    d.text(xy, value, font=font(size), fill=fill, anchor=anchor)
def node(d, rect, title, sub='', color=BLUE):
    d.rounded_rectangle(rect, radius=16, fill='white', outline=color, width=3)
    x=(rect[0]+rect[2])/2; y=(rect[1]+rect[3])/2
    text(d,(x,y-17 if sub else y),title,27,anchor='mm')
    if sub: text(d,(x,y+22),sub,21,MUTED,anchor='mm')
def arrow(d, start, end, color=BLUE, width=3, dashed=False):
    x,y=start; ex,ey=end
    if dashed:
        length=math.hypot(ex-x,ey-y)
        for t in range(0,int(length),20):
            a=t/length; b=min(t+11,length)/length
            d.line((x+(ex-x)*a,y+(ey-y)*a,x+(ex-x)*b,y+(ey-y)*b),fill=color,width=width)
    else: d.line((start,end),fill=color,width=width)
    theta=math.atan2(ey-y,ex-x)
    pts=[end,(ex-15*math.cos(theta-.4),ey-15*math.sin(theta-.4)),(ex-15*math.cos(theta+.4),ey-15*math.sin(theta+.4))]
    d.polygon(pts,fill=color)
def header(d,title,subtitle):
    text(d,(65,40),title,43)
    text(d,(65,99),subtitle,25,MUTED)
def component():
    im=Image.new('RGB',(W,1180),BG); d=ImageDraw.Draw(im)
    header(d,'URL Shortener | Scalable Architecture','NGINX / two stateless Spring Boot replicas / shared Redis / PostgreSQL')
    node(d,(560,160,1040,245),'Clients','API clients and browsers')
    arrow(d,(800,245),(800,285))
    node(d,(450,285,1150,390),'NGINX load balancer','localhost:8080 / least connections / no response cache')
    d.line([(800,390),(800,435),(420,435)],fill=BLUE,width=3)
    d.line([(800,435),(1180,435)],fill=BLUE,width=3)
    arrow(d,(420,435),(420,475));arrow(d,(1180,435),(1180,475))
    for x,title in [(420,'app-1: Spring Boot'),(1180,'app-2: Spring Boot')]:
        node(d,(x-290,475,x+290,595),title,'MVC / cache / bounded async workers / JDBC')
    text(d,(800,640),'Per replica: 2 analytics workers / 1,000 queued events (lossy)',24,MUTED,anchor='mm')
    # Both applications independently access both shared dependencies.
    d.line([(420,595),(420,685),(1180,685),(1180,595)],fill=BLUE,width=3)
    arrow(d,(420,685),(420,760));arrow(d,(1180,685),(1180,760))
    node(d,(130,760,710,885),'Shared Redis','Expiring mapping cache (disposable)',TEAL)
    node(d,(890,760,1470,885),'PostgreSQL 18','Mappings + events + idempotency',TEAL)
    node(d,(890,950,1470,1030),'Persistent volume','Committed data survives restarts',TEAL)
    arrow(d,(1180,885),(1180,950),TEAL)
    text(d,(130,960),'Cache hit: skip mapping SELECT',24,MUTED)
    text(d,(130,1000),'Async workers write events to PostgreSQL',24,MUTED)
    text(d,(65,1090),'One migration job before app readiness; retention runs on app-1 only.',25,TEAL)
    text(d,(65,1132),'Single-host demo: NGINX, Redis, PostgreSQL, and the host remain failure points.',25,MUTED)
    im.save(OUT/'component-architecture.png')
def sequence():
    im=Image.new('RGB',(W,1580),BG); d=ImageDraw.Draw(im)
    header(d,'URL Shortener | Async Redirect Sequence','Redis accelerates reads; bounded workers persist events after submission')
    xs=[150,465,780,1095,1410]
    for x,title,sub in zip(xs,['Client / NGINX','App replica','Shared Redis','Local worker','PostgreSQL'],['GET / HEAD','Redirect policy','Mapping cache','Lossy queue','Source of truth']):
        node(d,(x-140,175,x+140,275),title,sub)
        for y in range(290,1440,20):d.line((x,y,x,y+10),fill='#b0c0ce',width=2)
    def msg(a,b,y,label,reply=False):
        arrow(d,(xs[a],y),(xs[b],y),TEAL if reply else BLUE,dashed=reply)
        text(d,((xs[a]+xs[b])/2,y-25),label,22,anchor='mm')
    msg(0,1,340,'GET /r/:code')
    msg(1,2,410,'GET redirect:v1:{code}')
    msg(2,1,485,'Mapping hit / miss',True)
    d.rounded_rectangle((315,605,1550,855),radius=12,fill='#e7f2ee',outline=TEAL,width=2)
    text(d,(340,626),'CACHE MISS / ERROR ONLY',22,TEAL)
    msg(1,4,695,'Bounded mapping SELECT from primary')
    msg(4,1,765,'Mapping + original read time',True)
    msg(1,2,835,'Fill: absolute deadline')
    d.rounded_rectangle((315,900,1550,990),radius=12,fill='#fff4e7',outline='#d7ac73',width=2)
    text(d,(340,923),'Check lifecycle and freshness; missing -> 404; inactive -> 410;',24)
    text(d,(340,959),'lookup failure/overload -> 503. Never extend stale entries.',24)
    msg(1,3,1060,'Active GET: try bounded enqueue (HEAD skips)')
    msg(1,0,1135,'302 + no-store',True)
    text(d,(690,1130),'Enqueue rejection: meter loss, still redirect',22,MUTED)
    msg(3,4,1220,'Event INSERT')
    msg(4,3,1300,'Commit / failure',True)
    text(d,(690,1370),'Worker may run before or after response; HTTP does not wait.',23,TEAL)
    text(d,(65,1450),'Queue overload/crash can lose events; stats are eventually consistent.',24,TEAL)
    text(d,(65,1505),'Strict mode checks DB; cached disabling retains the bounded freshness window.',24,MUTED)
    im.save(OUT/'redirect-sequence.png')
if __name__=='__main__':
    component(); sequence()
    print('Rendered component-architecture.png and redirect-sequence.png')
