import subprocess,json,sys,os
q=json.load(open('/Users/jurekzsl/Documents/code/OhioFiles-Android/verification/parity-fixtures.json'))
f=next(f for f in q['files'] if f['filename']==sys.argv[1])
subprocess.run(['adb','-s','8bcdc583','shell','am','start','-a','android.intent.action.VIEW','-d','https://ohiofiles.cloud/'+f['slug'],'-n',os.environ.get('OHIO_QA_PACKAGE','cloud.ohiofiles.app')+'/.MainActivity'],check=True)
