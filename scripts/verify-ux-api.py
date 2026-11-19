"""Exercise document/review APIs using only a disposable project."""
import os
import uuid
import requests

base = os.environ.get('FINFLOW_TEST_URL', 'http://127.0.0.1:8080')
client = requests.Session()

def call(method, path, **kwargs):
    response = client.request(method, base + path, timeout=30, **kwargs)
    response.raise_for_status()
    return response.json() if response.content else None

call('POST', '/api/auth/login', json={
    'username': os.environ.get('FINFLOW_TEST_USER', 'default'),
    'password': os.environ['FINFLOW_TEST_PASSWORD'],
})
project = call('POST', '/api/projects', json={'name': 'UX regression ' + str(uuid.uuid4())[:8], 'description': 'Disposable API verification'})
pid = project['id']
try:
    file = call('POST', f'/api/projects/{pid}/files', files={'file': ('review.md', b'# Original\n', 'text/markdown')})
    fid = file['id']
    path = f'/api/native-documents/files/{fid}'
    assert call('GET', path)['version'] == 1
    call('POST', f'/api/projects/{pid}/artifact-reviews', json={'resourceId': fid, 'version': 1})
    assert call('PUT', path, json={'content': '# Edited\n', 'expectedVersion': 1})['version'] == 2
    old = call('GET', path + '?requestedVersion=1')
    assert old['content'] == '# Original\n' and not old['editable']
    conflict = client.put(base + path, json={'content': 'stale', 'expectedVersion': 1}, timeout=30)
    assert conflict.status_code >= 400
    assert call('GET', path)['content'] == '# Edited\n'
    stale_review = client.post(base + f'/api/projects/{pid}/artifact-reviews', json={'resourceId': fid, 'version': 1}, timeout=30)
    assert stale_review.status_code >= 400
    call('POST', f'/api/projects/{pid}/artifact-reviews', json={'resourceId': fid, 'version': 2})
    assert {r['version'] for r in call('GET', f'/api/projects/{pid}/artifact-reviews')} == {1, 2}
    folder = call('POST', f'/api/projects/{pid}/workspace/folders', json={'name': 'Reviewed', 'rootKind': 'FILES'})
    workspace = call('GET', f'/api/projects/{pid}/workspace')
    resource = next(r for r in workspace['resources'] if r['id'] == fid)
    call('PUT', f"/api/projects/{pid}/workspace/resources/{resource['resourceType']}/{fid}/folder", json={'folderId': folder['id']})
    call('DELETE', f'/api/files/{fid}')
    assert not call('GET', f'/api/projects/{pid}/workspace')['resources']
    print('PASS: version save/history/conflict, exact-version review, folder move, deletion')
finally:
    call('DELETE', f'/api/projects/{pid}')
    print('Disposable project removed')
