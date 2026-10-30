import subprocess,hashlib,json,urllib.request,urllib.parse,urllib.error,os,time
SERIAL='8bcdc583'
PACKAGE=os.environ.get('OHIO_QA_PACKAGE','cloud.ohiofiles.app')
def adb(*args):return subprocess.check_output(['adb','-s',SERIAL,*args])
def var(b,p):
 n=0;s=0
 while True:
  c=b[p];p+=1;n|=(c&127)<<s
  if not c&128:return n,p
  s+=7
def fields(b):
 p=0;r=[]
 while p<len(b):
  tag,p=var(b,p);w=tag&7
  if w==2:
   n,p=var(b,p);v=b[p:p+n];p+=n
  elif w==0:v,p=var(b,p)
  elif w in (1,5):
   n=8 if w==1 else 4;v=b[p:p+n];p+=n
  else:raise ValueError(w)
  r.append((tag>>3,w,v))
 return r
def prefs():
 raw=adb('exec-out','su','-c','cat /data/user/0/'+PACKAGE+'/files/datastore/ohiofiles_prefs.preferences_pb')
 out={}
 for _,_,entry in fields(raw):
  m={n:v for n,w,v in fields(entry)};name=m[1].decode()
  val=fields(m[2])[0];v=val[2];out[name]=v.decode() if isinstance(v,bytes) else v
 return out
p=prefs();key=p['session_key'];server=p.get('server_url','https://api.ohiofiles.cloud').rstrip('/')
def api(path,method='GET',body=None,**query):
 query.setdefault('sessionKey',key)
 u=server+'/'+path.lstrip('/')+'?'+urllib.parse.urlencode({k:v for k,v in query.items() if v is not None})
 payload=None if body is None else json.dumps(body).encode()
 req=urllib.request.Request(u,data=payload,method=method,headers={'User-Agent':'okhttp/4.12.0','Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=60) as res:return json.load(res)
 except urllib.error.HTTPError as e:
  raise RuntimeError('QA API HTTP '+str(e.code)) from None
