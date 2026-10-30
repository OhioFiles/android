import json,subprocess
tree=json.loads(subprocess.check_output(['/Users/jurekzsl/.local/bin/android','layout','--device=8bcdc583','--no-idle']))
def walk(node):
 if 'text' in node or 'content-desc' in node or node.get('class','').endswith(('EditText','Slider')):
  print(json.dumps({k:node[k] for k in ['class','text','content-desc','state','interactions','center','bounds'] if k in node},ensure_ascii=False))
 for child in node.get('children',[]):walk(child)
for window in tree:
 if window.get('type')=='APPLICATION':
  print('WINDOW',window.get('window-title'));[walk(n) for n in window.get('content',[])]
