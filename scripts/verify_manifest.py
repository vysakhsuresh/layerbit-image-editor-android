#!/usr/bin/env python3
"""Fail if the merged app requests any networking or photo/storage permission."""
import sys,xml.etree.ElementTree as ET
from pathlib import Path
paths=list(Path('app/build/intermediates').glob('**/AndroidManifest.xml'))
paths=[p for p in paths if 'merged_manifests' in str(p) and 'androidtest' not in str(p).lower()]
if not paths:sys.exit('No merged app manifest; run assembleDebug first.')
android='{http://schemas.android.com/apk/res/android}'
for p in paths:
    root=ET.parse(p).getroot()
    forbidden={'android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE','android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VIDEO'}
    declared={e.get(android+'name') for e in root.findall('uses-permission')}
    if forbidden & declared:sys.exit(f'Forbidden permissions in {p}: {forbidden & declared}')
    app=root.find('application')
    if app.get(android+'allowBackup')!='false':sys.exit('Private projects must not be backed up.')
    if app.get(android+'dataExtractionRules')!='@xml/data_extraction_rules':sys.exit('Device-transfer exclusions must be declared.')
    for provider in app.findall('provider'):
        if provider.get(android+'name')=='androidx.core.content.FileProvider' and provider.get(android+'exported')!='false':sys.exit('FileProvider must not be exported.')
domains={'root','file','database','sharedpref','external','device_root','device_file','device_database','device_sharedpref'}
rules=ET.parse('app/src/main/res/xml/data_extraction_rules.xml').getroot()
for mode in ('cloud-backup','device-transfer'):
    excluded={e.get('domain') for e in rules.find(mode).findall('exclude') if e.get('path')=='.'}
    if not domains.issubset(excluded):sys.exit(f'Missing private-data exclusions in {mode}')
print(f'Privacy manifest check passed ({len(paths)} merged manifests); backup/transfer exclusions verified.')
