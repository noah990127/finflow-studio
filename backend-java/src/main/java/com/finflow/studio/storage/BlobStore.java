package com.finflow.studio.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface BlobStore {
    record StoredObject(String location, long size, String checksum) { }

    StoredObject put(String key, InputStream input, long maxBytes);

    default StoredObject putBytes(String key, byte[] content, long maxBytes) {
        return put(key, new java.io.ByteArrayInputStream(content), maxBytes);
    }

    Path materialize(String location);

    void delete(String location);
}
