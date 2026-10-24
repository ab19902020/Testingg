import json, urllib.request, urllib.parse, sys
tok=None; names=[]
while True:
    url="https://storage.googleapis.com/storage/v1/b/mediapipe-assets/o?fields=items(name,size),nextPageToken&maxResults=1000"
    if tok: url+="&pageToken="+urllib.parse.quote(tok)
    d=json.load(urllib.request.urlopen(url, timeout=60))
    names += [(i['name'], int(i['size'])) for i in d.get('items',[])]
    tok=d.get('nextPageToken')
    if not tok: break
print(len(names))
for n,s in names:
    l=n.lower()
    if l.endswith(('.jpg','.png','.jpeg','.mp4','.webp')) and not 'mask' in l and not 'golden' in l and not 'expected' in l:
        print(n, s)
