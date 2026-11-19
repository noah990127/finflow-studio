package com.finflow.studio.knowledge.tacit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class TacitJson {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private TacitJson() { }

    static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("知识台账不能序列化", e); }
    }

    static Object value(String raw) {
        try { return JSON.readValue(raw, Object.class); }
        catch (Exception e) { throw new IllegalStateException("审核快照无法读取", e); }
    }

    static Map<String, Object> object(String value) {
        try { return JSON.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("知识台账记录无法读取", e); }
    }

    static List<String> strings(String value) {
        try { return JSON.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("知识来源清单无法读取", e); }
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalStateException("知识台账状态格式异常");
        return (Map<String, Object>) value;
    }
}
