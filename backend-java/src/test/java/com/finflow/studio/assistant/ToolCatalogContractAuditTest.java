package com.finflow.studio.assistant;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class ToolCatalogContractAuditTest {
    @TestFactory Stream<DynamicTest> everyRegisteredToolHasConsistentSchemaAndRejectsUnknownFields() {
        return AssistantCapabilityRegistry.ids().stream().sorted().map(id -> DynamicTest.dynamicTest(id, () -> {
            var capability=AssistantCapabilityRegistry.find(id).orElseThrow();
            var schema=capability.inputSchema();
            var properties=(Map<?,?>)schema.get("properties");
            assertThat(properties.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrderElementsOf(capability.arguments());
            assertThat(capability.arguments()).containsAll(((List<?>)schema.get("required")).stream().map(Object::toString).toList());
            var valid=example(schema);
            if (id.equals("tacit.save_batch")) valid.put("section", "rules");
            if (id.equals("browser.act")) valid.put("action", "click");
            if (id.equals("resource.prepare_delete")) valid.put("resource_ids",List.of("resource"));
            AssistantToolContracts.validate(id,valid);
            var unknown=new LinkedHashMap<>(valid); unknown.put("not_a_parameter",true);
            assertThatThrownBy(() -> AssistantToolContracts.validate(id,unknown)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持字段");
            for (var key : capability.arguments()) {
                var nullValue=new LinkedHashMap<>(valid); nullValue.put(key,null);
                assertThatThrownBy(() -> AssistantToolContracts.validate(id,nullValue)).as(id+":"+key).isInstanceOf(IllegalArgumentException.class);
                var type=Objects.toString(((Map<?,?>)properties.get(key)).get("type"));
                var wrong=new LinkedHashMap<>(valid); wrong.put(key,"string".equals(type)?42:"wrong-type");
                assertThatThrownBy(() -> AssistantToolContracts.validate(id,wrong)).as(id+":"+key).isInstanceOf(IllegalArgumentException.class);
            }
        }));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> example(Map<String,Object> schema) {
        var result=new LinkedHashMap<String,Object>();
        var properties=(Map<String,Map<String,Object>>)schema.get("properties");
        for (var key : (List<String>)schema.getOrDefault("required",List.of())) result.put(key,value(properties.get(key)));
        if (schema.get("anyOf") instanceof List<?> options) {
            var branch=(Map<String,Object>)options.getFirst();
            for(var key:(List<String>)branch.get("required")) result.put(key,value(properties.get(key)));
        }
        return result;
    }
    private Object value(Map<String,Object> schema) {
        return switch(Objects.toString(schema.get("type"))) {
            case "object" -> example(schema);
            case "array" -> {
                @SuppressWarnings("unchecked") var item=(Map<String,Object>)schema.get("items");
                int size=((Number)schema.getOrDefault("minItems",0)).intValue();
                yield java.util.stream.IntStream.range(0,size).mapToObj(i->value(item)).toList();
            }
            case "integer" -> schema.getOrDefault("minimum",1);
            case "number" -> 1;
            case "boolean" -> true;
            default -> schema.get("enum") instanceof List<?> values ? values.getFirst() : "uri".equals(schema.get("format")) ? "https://example.com" : "sample";
        };
    }
    @Test void rejectsBoundsNullArrayEntriesAndInvalidUrls() {
        assertThatThrownBy(()->AssistantToolContracts.validate("knowledge.discover_external_sources",Map.of("topic","news","max_sources",21))).hasMessageContaining("不能大于");
        assertThatThrownBy(()->AssistantToolContracts.validate("knowledge.discover_external_sources",Map.of("topic","x".repeat(501)))).hasMessageContaining("500");
        assertThatThrownBy(()->AssistantToolContracts.validate("source.verify",Map.of("url","file:///tmp/private"))).hasMessageContaining("HTTP/HTTPS");
        assertThatThrownBy(()->AssistantToolContracts.validate("source.add_verified",Map.of("project_id","p","sources",Arrays.asList((Object)null)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AssistantToolContracts.validate("source.add_verified",Map.of("project_id","p","sources",List.of(Map.of("name","missing-url"))))).hasMessageContaining("url");
        assertThatThrownBy(()->AssistantToolContracts.validate("resource.prepare_delete",Map.of("project_id","p","scope","selected"))).hasMessageContaining("resource_ids");
        AssistantToolContracts.validate("resource.add",Map.of("project_id","p","file_name","note.md","content","text"));
    }
    @Test void unimplementedOptionsAreNotAdvertisedOrSilentlyAccepted() {
        for(var item:Map.of("resource.read","range","knowledge.parse","parser_options",
                "dataset.connect","credentials_ref","deliverable.export","destination").entrySet()) {
            assertThat(AssistantCapabilityRegistry.find(item.getKey()).orElseThrow().arguments()).doesNotContain(item.getValue());
        }
        assertThatThrownBy(()->AssistantToolContracts.validate("dataset.add_source",Map.of("project_id","p","name","db","connection",Map.of())))
                .hasMessageContaining("完整参数");
        assertThatThrownBy(()->AssistantToolContracts.validate("browser.explore",Map.of("site","demo","goal","read","parameters",Map.of("date",123))))
                .hasMessageContaining("parameters.date");
    }
}
