#!/usr/bin/env python3
"""Fail if the merged app requests any networking or photo/storage permission."""
import sys,xml.etree.ElementTree as ET
from pathlib import Path
paths=list(Path('app/build/intermediates').glob('**/AndroidManifest.xml'))
paths=[p for p in paths if 'debug' in str(p).lower() and 'merged_manifests' in str(p)]
if not paths:sys.exit('No merged debug manifest; run assembleDebug first.')
android='{http://schemas.android.com/apk/res/android}'
for p in paths:
    root=ET.parse(p).getroot()
    forbidden={'android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE','android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VIDEO'}
    declared={e.get(android+'name') for e in root.findall('uses-permission')}
    if forbidden & declared:sys.exit(f'Forbidden permissions in {p}: {forbidden & declared}')
    app=root.find('application')
    if app.get(android+'allowBackup')!='false':sys.exit('Private projects must not be backed up.')
    for provider in app.findall('provider'):
        if provider.get(android+'name')=='androidx.core.content.FileProvider' and provider.get(android+'exported')!='false':sys.exit('FileProvider must not be exported.')
print(f'Privacy manifest check passed ({len(paths)} merged manifests).')
