import sys
import zipfile

src, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]

zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(out, 'w')

for item in zin.infolist():
    if item.filename == 'classes.dex':
        continue
    data = zin.read(item.filename)
    zout.writestr(item, data)

zout.write(dex, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
zout.close()
zin.close()
print('merged ->', out)
